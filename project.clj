(defproject alembic "0.3.3-SNAPSHOT"
  :description
  "A library for use In the REPL.  Add dependencies to your classpath,
  reload your project.clj file, and invoke leiningen tasks."
  :url "https://github.com/pallet/alembic"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[lein-as-resource "2.5.0"]
                 [org.flatland/classlojure "0.7.0"]
                 [org.tcrawley/dynapath "0.2.3"]]
  :exclusions [[org.clojure/clojure]]
  :profiles {:provided
             {:dependencies [[org.clojure/clojure "1.4.0"]]}})
