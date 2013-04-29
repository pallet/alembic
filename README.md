# alembic

Alembic is a clojure library that allows you to distill jars onto your
classpath.  It uses leiningen and pomegranate to resolve the jars, classlojure
to isolate leiningen and its dependencies, and dynapath to modify the classpath.

This means you can use lein and pomegranate without their dependencies
interfering with your project classpath.  The only dependencies added are
classlojure and dynapath - both smallish libraries (though I wish classlojure
didn't depend on useful, which is rather a large dependency for the one function
it uses from the library).

## Project Dependency

Use the [lein-alembic](https://github.com/pallet/lein-alembic) plugin for
leiningen.

```clj
:plugins [[lein-alembic "0.1.0"]]
```

It is possible to use Alembic without using the plugin by adding the alembic
library directly to your project's dev or test dependencies.

```clj
[alembic "0.1.0"]
```

## Usage

To add a dependency to the classpath, use the `distill` function, passing a
leiningen style dependency vector.

```clj
(alembic.still/distill '[org.clojure/tools.logging "0.2.0"])
```

If the dependency is added to the classpath, `distill` returns a sequence of
maps, where each map represents a dependent jar.  Those jars without a current
version on the classpath will be added to the classpath.  The jars with a
version already on the classpath are not added to the classpath, and the
currently loaded version is reported on the :current-version key.

The distill function returns nil, with no side-effects, if the dependency is
already on the classpath.

You can query the dependencies that have been added with the
`dependencies-added` function, which returns a sequence of leiningen style
dependency vectors.

You can lookup the dependency jars for the distilled dependencies, using the
`dependency-jars` function.

The `conflicting-versions` function returns a sequence of dependencies for a
distilled dependency, where the dependency jar version doesn't match the version
currently on the classpath.

## Support and Discussion

Discussion of alembic, either on the
[clojure-tools](https://groups.google.com/forum/?fromgroups#!forum/clojure-tools)
google group, or on #clojure or #pallet on freenode IRC.

## License

Copyright © 2013 Hugo Duncan

Distributed under the Eclipse Public License.
