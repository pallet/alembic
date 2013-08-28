# alembic

[Repository](https://github.com/pallet/alembic) &#xb7;
[Issues](https://github.com/pallet/alembic/issues) &#xb7;
[API docs](http://palletops.com/alembic/0.1/api) &#xb7;
[Annotated source](http://palletops.com/alembic/0.1/annotated/uberdoc.html) &#xb7;
[Release Notes](https://github.com/pallet/alembic/blob/develop/ReleaseNotes.md)

Alembic is a clojure library that allows you to distill jars onto your classpath
in a running JVM instance.  It uses leiningen and pomegranate to resolve the
jars, classlojure to isolate leiningen and its dependencies, and dynapath to
modify the classpath.

This means you can use lein and pomegranate without their dependencies
interfering with your project classpath.  The only dependencies added
are classlojure and dynapath - both small libraries with no transitive
dependencies.

## Project Dependency

To use Alembic with nREPL or any other clojure REPL, you will need to add
Alembic to you development dependencies.  For a leiningen based project, you can
do this by adding it to the `:dependencies` vector of the `:dev` profile in
`project.clj`.

```clj
:profiles {:dev {:dependencies [[alembic "0.1.4"]]}}
```

You can enable Alembic on all you projects, by adding it to the `:dependencies`
vector of the `:user` profile in `~/.lein/profiles.clj`.

## Usage

### Reloading project.clj

If you modify the dependencies in your `project.clj` file, you can load the
modified dependencies with `load-project`.

This will add all non-conflicting dependency changes.  Only new
dependencies are considered non-conflicting.  New versions of existing
dependencies are not loaded.  Removed dependencies are not unloaded.

### Adding Ad-Hoc Dependencies

To add a dependency to the classpath, use the `distill` function, passing a
leiningen style dependency vector.

```clj
(alembic.still/distill '[org.clojure/tools.logging "0.2.0"])
```
You can pass a sequence of dependencies to add, or just a single
dependency as in the example above.

`distill` prints the dependencies added to the classpath, and those
not added due to conflicts.

The distill function returns with no side-effects, if the dependency's
jars are already on the classpath.

By default, `distill` uses the repositories in the current lein project.  You
can override this by passing a map of lein style repository information to the
`:repositories` option.  The `project-repositories` function can be used to
obtain the lein project repositories, should you want to adapt these to pass as
an `:repositories` argument.


For programmatic use, `distill*` returns a sequence of maps, where
each map represents a dependent jar.  Those jars without a current
version on the classpath will be added to the classpath.  The jars
with a version already on the classpath are not added to the
classpath, and the currently loaded version is reported on the
:current-version key.

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
