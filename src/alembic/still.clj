(ns alembic.still
  "

Track the added dependencies, so that we can query for addition to
project.clj

Can not create a classloader with a jar inside a jar
http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4388202
"
  (:require
   [classlojure.core :refer [base-classloader classlojure ext-classloader]
    :as classlojure]
   [clojure.java.io :refer [copy file reader resource]]
   [dynapath.util :as util])
  (:import
   java.util.Properties))

;;; ## Utilities
(defn classpath-urls
  "Return the current classpath."
  []
  (util/classpath-urls base-classloader))

(defn extract-jar
  "Extract a jar on the classpath to the filesystem, returning its URL."
  [^String jar-path]
  {:pre [(.endsWith jar-path ".jar")]}
  (let [jar-url (resource jar-path)
        f (java.io.File/createTempFile
           (subs jar-path 0 (- (count jar-path) 4)) ".jar")
        ]
    (.deleteOnExit f)
    (with-open [is (.getContent jar-url)]
      (copy is f))
    (.toURL f)))


;;; ## Still
(defonce ^{:doc "Classpath URLs for a still"}
  alembic-cp
  (map extract-jar ["lein-standalone.jar"]))

;; (defn alembic-classloader []
;;   (doto (alembic.JarClassLoader.
;;          (into-array java.net.URL alembic-cp) ext-classloader)
;;     (.loadClass "clojure.lang.RT")
;;     (#'classlojure/eval-in* '(require 'clojure.main))))

(def cl (apply classlojure alembic-cp))

(defn alembic-classloader
  "Return a classloader for alembic to use to resolve dependencies"
  []
  (doto (apply classlojure alembic-cp)
    (classlojure/eval-in
     `(require '[cemerick.pomegranate.aether :as ~'aether]))))

(defn make-still
  "Create an still that that can distill jars into the specified classloader."
  [classloader]
  (when-not (util/addable-classpath? classloader)
    (throw (ex-info
            "Alembic can not manipulate specified ClassLoader."
            {:classloader classloader
             :reason :not-addable-with-dynapath})))
  {:dependencies []
   :jars {}
   :classloader classloader
   :alembic-classloader (alembic-classloader)})

;;; Our still
(defonce the-still (atom (make-still base-classloader)))

;; (defn eval-in [still form & args]
;;   {:pre [(:alembic-classloader @still)]}
;;   (apply classlojure/eval-in (:alembic-classloader @still) form args))

(defn project-repositories
  "Load project repositories from leiningen."
  ([still project-file]
     (classlojure/eval-in
      (:alembic-classloader @still)
      `(do
         (require '[leiningen.core.project :as ~'project])
         (:repositories (leiningen.core.project/read ~project-file)))))
  ([still]
     (project-repositories still "project.clj")))

(defn resolve-dependency
  [still dependency repositories]
  (classlojure/eval-in
   (:alembic-classloader @still)
   `(mapv
     (fn [dep#]
       (letfn [(apath# [^java.io.File f#] (.getAbsolutePath f#))]
         {:coords dep#
          :jar (-> dep# meta :file apath#)}))
     (keys
      (aether/resolve-dependencies
       :coordinates '[~dependency]
       :repositories ~repositories)))))

(defn properties-path
  [group-id artifact-id]
  (str "META-INF/maven/" group-id "/" artifact-id "/pom.properties"))

(defn coords->ids
  "Convert coords to a map of :group-id and :artifact-id."
  [[artifact version :as coords]]
  {:group-id (or (namespace artifact) artifact)
   :artifact-id (symbol (name artifact))})

(defn meta-inf-properties-url
  "Return a URL for the META-INF properties file for the given `coords`."
  [still coords]
  (let [{:keys [group-id artifact-id]} (coords->ids coords)]
    (classlojure/eval-in
     (:classloader @still)
     `(when-let [r# (resource ~(properties-path group-id artifact-id))]
        (.toString r#)))))

(defn meta-inf-version
  "Return a version string for the currently loaded version of the given
  `coords`."
  [still coords]
  (let [{:keys [group-id artifact-id]} (coords->ids coords)]
    (classlojure/eval-in
     (:classloader @still)
     `(when-let [r# (resource ~(properties-path group-id artifact-id))]
        (with-open [rdr# (reader r#)]
          (let [properties# (doto (Properties.) (.load rdr#))]
            (.getProperty properties# "version")))))))

(defn current-dep-versions
  [still dep-jars]
  (for [{:keys [coords] :as dep} dep-jars]
    (if-let [current-version (meta-inf-version still coords)]
      (assoc dep :current-version current-version)
      dep)))

(defn warn-mismatch-versions
  [dep-jars]
  (doseq [{:keys [coords current-version]} dep-jars
          :let [[artifact version] coords]]
    (when (and current-version (not= current-version version))
      (println "WARN:" artifact "version" version "requested, but "
               current-version "already on classpath."))))

(defn add-dep-jars
  [still dep-jars]
  (let [{:keys [classloader]} @still]
    (doseq [{:keys [coords current-version jar]} dep-jars
            :let [[artifact version] coords]]
      (when-not current-version
        (util/add-classpath-url classloader (.toURL (file jar)))))))


(defn add-dependency
  "Add a dependency to the classpath.  Returns a sequence of maps, each
containing a :coords vector, a :jar path and possibly a :current-version
string.  If the dependency is already on the classpath, returns nil."
  [still dependency repositories]
  (when-not (meta-inf-properties-url still dependency)
    (let [dep-jars (->> (resolve-dependency still dependency repositories)
                        (current-dep-versions still))]
      (warn-mismatch-versions dep-jars)
      (add-dep-jars still dep-jars)
      (swap! still (fn [m]
                     (-> m
                         (update-in [:dependencies] conj dependency)
                         (update-in [:jars] assoc dependency dep-jars))))
      dep-jars)))

(defn distill
  [dependency & {:keys [repositories still]
                 :or {still the-still
                      repositories (project-repositories still)}}]
  (add-dependency still dependency repositories))

(defn dependencies-added
  ([still]
     (:dependencies @still))
  ([] (dependencies-added the-still)))

(defn dependency-jars
  ([still]
     (:jars @still))
  ([] (dependencies-added the-still)))

(defn conflicting-versions
  "Return a sequence of possibly conflicting versions of jars required for the
specified dependency (dependency must have been added with `add-dependency`)."
  ([dependency still]
     (filter :current-version (get (dependency-jars still) dependency)))
  ([dependency]
     (conflicting-versions dependency the-still)))
