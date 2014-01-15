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
   [clojure.pprint :refer [pprint]]
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
           (subs jar-path 0 (- (count jar-path) 4)) ".jar")]
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
     (project-repositories still "project.clj"))
  ([]
     (project-repositories the-still)))

(defn resolve-dependencies
  [still dependencies repositories proxy]
  (classlojure/eval-in
   (:alembic-classloader @still)
   `(do
      (require '[leiningen.core.classpath :as ~'cp])
      (mapv
       (fn [dep#]
         (letfn [(apath# [^java.io.File f#] (.getAbsolutePath f#))]
           {:coords dep#
            :jar (-> dep# meta :file apath#)}))
       (keys
        (aether/resolve-dependencies
         :coordinates '~(vec dependencies)
         :repositories ~repositories
         :proxy (or ~proxy (cp/get-proxy-settings))))))))

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
      (println "WARN:" artifact "version" version "requested, but"
               current-version "already on classpath."))))

(defn conflicting-version?
  "Predicate to check for a conflicting version."
  [{:keys [coords current-version]}]
  (and current-version (not= current-version (second coords))))

(defn add-dep-jars
  "Add any non-conflicting dependency jars.  Returns the sequence of
dependency jar maps of the loaded jars."
  [still dep-jars]
  (let [{:keys [classloader]} @still
        deps (remove :current-version dep-jars)]
    (doseq [{:keys [jar]} deps]
      (util/add-classpath-url classloader (.toURL (file jar))))
    deps))

(defn add-dependencies
  "Add dependencies to the classpath. Returns a sequence of maps, each
containing a `:coords` vector, a `:jar` path and possibly a
`:current-version` string. If the optional parameter :verbose is
true (the default), then WARN messages will be printed to the console
if a version of a library is requested and the classpath already
contains a different version of the same library."
  [still dependencies repositories {:keys [verbose proxy] :as opts
                                    :or {verbose true}}]
  (let [dep-jars (->> (resolve-dependencies still dependencies repositories proxy)
                      (current-dep-versions still))]
    (when verbose (warn-mismatch-versions dep-jars))
    (add-dep-jars still dep-jars)
    (swap! still (fn [m]
                   (-> m
                       (update-in [:dependencies]
                                  #(distinct (concat % dependencies)))
                       (update-in [:jars]
                                  #(distinct (concat % dep-jars))))))
    dep-jars))

(defn print-coords
  "Pretty print the dependency coordinates of a sequence of dependencies."
  [deps]
  (pprint (vec (sort-by first (map :coords deps)))))

(defn distill*
  "Add dependencies to the classpath.  Returns a sequence of dependency maps.

`dependencies` can be a coordinate vector, or a sequence of such
vectors.

`:repositories`
: specify a map of leiningen style repository definitions to be used when
  resolving.  Defaults to the repositories specified in the current lein
  project.

`:still`
: specifies an alembic still to use.  This would be considered advanced
  usage (see the tests for an example).

`:verbose`
: specifies whether WARN messages should be printed to the console if
  a version of library is requests and there is already a different
  version of the same library in the classpath. Defaults to true

`:proxy`
: proxy configuration map (the host scheme and type must match).
  If not specified (or nil), the proxy configuration is read from
  environment variables (http_proxy, http_no_proxy, no_proxy).
    :host - proxy hostname
    :type - http  (default) | http | https
    :port - proxy port
    :non-proxy-hosts - The list of hosts to exclude from proxying, may be null
    :username - username to log in with, may be null
    :password - password to log in with, may be null
    :passphrase - passphrase to log in wth, may be null
    :private-key-file - private key file to log in with, may be null"
  [dependencies {:keys [repositories still verbose proxy]
                 :or {still the-still
                      verbose true}}]
  (let [repositories (into {} (or repositories
                                  (project-repositories still)))]
    (add-dependencies
     still
     (if (every? vector? dependencies) dependencies [dependencies])
     repositories
     {:verbose verbose :proxy proxy})))

(defn distill
  "Add dependencies to the classpath.

`dependencies` can be a coordinate vector, or a sequence of such vectors.

`:repositories`
: specify a map of leiningen style repository definitions to be used when
  resolving.  Defaults to the repositories specified in the current lein
  project.

`:still`
: specifies an alembic still to use.  This would be considered advanced
  usage (see the tests for an example).

`:verbose`
: specifies whether WARN messages should be printed to the console if
  a version of library is requests and there is already a different
  version of the same library in the classpath. Defaults to true

`:proxy`
: proxy configuration map (the host scheme and type must match).
  If not specified (or nil), the proxy configuration is read from
  environment variables (http_proxy, http_no_proxy, no_proxy).
    :host - proxy hostname
    :type - http  (default) | http | https
    :port - proxy port
    :non-proxy-hosts - The list of hosts to exclude from proxying, may be null
    :username - username to log in with, may be null
    :password - password to log in with, may be null
    :passphrase - passphrase to log in wth, may be null
    :private-key-file - private key file to log in with, may be null"
  [dependencies & {:keys [repositories still verbose proxy]
                   :or {still the-still
                        verbose true}
                   :as options}]
  (let [dep-jars (distill* dependencies options)
        loaded (remove conflicting-version? dep-jars)
        conflicting (filter conflicting-version? dep-jars)]
    (when (and verbose (seq loaded))
      (println "Loaded dependencies:")
      (print-coords loaded))
    (when (and verbose (seq conflicting))
      (println
       "Dependencies not loaded due to conflict with previous jars :")
      (print-coords conflicting))))

(defn load-project*
  "Load project.clj dependencies.  Returns a vector of jars required
for the dependencies.  Loads any of the jars that are not conflicting
with versions already on the classpath."
  [project-file {:keys [still verbose proxy] :as options}]
  (let [[dependencies repositories]
        (classlojure/eval-in
         (:alembic-classloader @still)
         `(do
            (require '[leiningen.core.project :as ~'project])
            (let [project# (leiningen.core.project/read ~project-file)]
              [(:dependencies project#)
               (:repositories project#)])))]
    (add-dependencies still dependencies (into {} repositories) options)))

(defn load-project
  "Load project.clj dependencies.  Prints the dependency jars that are
loaded, and those that were not loaded due to conflicts.

`:proxy`
: proxy configuration map (the host scheme and type must match).
  If not specified (or nil), the proxy configuration is read from
  environment variables (http_proxy, http_no_proxy, no_proxy).
    :host - proxy hostname
    :type - http  (default) | http | https
    :port - proxy port
    :non-proxy-hosts - The list of hosts to exclude from proxying, may be null
    :username - username to log in with, may be null
    :password - password to log in with, may be null
    :passphrase - passphrase to log in wth, may be null
    :private-key-file - private key file to log in with, may be null"
  ([project-file & {:keys [still verbose proxy]
                    :or {still the-still
                         verbose true}
                    :as options}]
     (let [dep-jars (load-project* project-file {:still still :verbose verbose :proxy proxy})
           loaded (remove conflicting-version? dep-jars)
           conflicting (filter conflicting-version? dep-jars)]
       (when (seq loaded)
         (println "Loaded dependencies:")
         (print-coords loaded))
       (when (seq conflicting)
         (println
          "Dependencies not loaded due to conflict with previous jars :")
         (print-coords conflicting))))
  ([project]
     (load-project project :still the-still))
  ([]
     (load-project "project.clj")))

(defn dependencies-added
  ([still]
     (:dependencies @still))
  ([] (dependencies-added the-still)))

(defn dependency-jars
  ([still]
     (:jars @still))
  ([] (dependency-jars the-still)))

(defn conflicting-versions
  "Return a sequence of possibly conflicting versions of jars required
for dependencies by the still)."
  ([still]
     (filter conflicting-version? (dependency-jars still)))
  ([] (conflicting-versions the-still)))
