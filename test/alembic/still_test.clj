(ns alembic.still-test
  (:require
   [alembic.still :as still]
   [classlojure.core :refer [base-classloader classlojure ext-classloader]
    :as classlojure]
   [clojure.java.io :refer [file]]
   [clojure.test :refer :all]
   [dynapath.util :as util]))

(defn clojure-path
  "Return the path of a clojure jar."
  []
  (let [cl (still/alembic-classloader)]
    (classlojure/eval-in
     cl
     `(letfn [(apath# [^java.io.File f#] (.getAbsolutePath f#))]
        (->
         (aether/resolve-dependencies
          :coordinates '[[org.clojure/clojure "1.4.0"]])
         keys
         first
         meta
         :file
         apath#)))))

(deftest clojure-path-test
  (is (string? (clojure-path))))

(def tools-logging '[org.clojure/tools.logging "0.2.0"])
(def clojure-dep '[org.clojure/clojure "1.5.1"])

(deftest distill-test
  (let [clojure-path (clojure-path)
        cl (classlojure (.toURL (file clojure-path)))
        still (atom (still/make-still cl))]
    (is (= [{:coords tools-logging}]
           (still/current-dep-versions still [{:coords tools-logging}])))
    (is (some #(re-find #"org/clojure/clojure/1.4.0/clojure-1.4.0.jar" %)
              (map #(.toString %) (util/all-classpath-urls cl))))
    (is (= [{:coords clojure-dep :current-version "1.4.0"}]
           (still/current-dep-versions still [{:coords clojure-dep}])))
    (is (nil? (classlojure/eval-in
               cl
               `(do
                  (try (require 'clojure.tools.logging)
                       (catch Exception _#))
                  (find-ns 'clojure.tools.logging))))
        "tools.logging not on the classpath")
    (let [n (count (util/all-classpath-urls cl))
          deps (still/distill* tools-logging {:still still})]
      (is (= 2 (count deps)) "Two dependency jars")
      (is (= (inc n)
             (count (util/all-classpath-urls cl)))
          "One dependency jar added")
      (is (= 1 (count (filter :current-version deps)))
          "One possible dependency conflict")
      (is (classlojure/eval-in
           cl
           `(do
              (require 'clojure.tools.logging)
              (name (ns-name (find-ns 'clojure.tools.logging)))))
          "tools.logging on the classpath")
      (is (= [tools-logging] (still/dependencies-added still))
          "distilled dependency listed")
      (is (= 1 (count (still/conflicting-versions still)))
          "possible distilled dependency conflict listed"))
    (is (nil? (still/distill tools-logging :still still))
        "cursory distill check")))

(deftest load-project-test
  (is (nil? (still/load-project)) "we can load ourself"))
