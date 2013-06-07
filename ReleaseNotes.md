# 0.1.3

- Don't read project.clj when :repositories passed to distill

# 0.1.2

- Allow disabling version mismatch messages at the console.

  They can be turned off by passing `false` to the option `:verbose?` in
  `distill` and `add-dependency`.

- Add no-arg arity to `project-dependencies`

  Describes the :repositories option for distill in the readme, mentioning
  project-dependencies.


# 0.1.1

- Add clojure 1.4 dependency

- Normalise :repositories to be a map

# 0.1.0

- Initial version
