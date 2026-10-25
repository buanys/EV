package ev.buan.hostcore.dependency

/**
 * Orders plugin ids so every dependency is installed before the containers that link against it.
 *
 * The host accepts containers one at a time and refuses a plugin whose dependencies have not been
 * accepted earlier in the same pass (`DEPENDENCY_MISSING`), so this order is not a nicety: with a
 * file-name order a plugin whose dependency happens to sort later can never load. Resolving the
 * declared graph here makes `<external storage>/EV/mods` insensitive to both file names and the
 * directory listing order.
 *
 * Pure Kotlin, no host vocabulary: the caller supplies `id -> declared dependency ids` and decides
 * what a cycle means for the user. Cycles are reported as closed rings (`a -> b -> a`) instead of a
 * boolean so the caller can name the plugins it refused.
 */
object DependencyResolver {

    /** Result of one resolution pass. */
    data class Resolution(
        /**
         * Ids that are not part of any cycle, each one listed after all of its dependencies.
         *
         * A plugin that depends on a cycle is still ordered here (its cyclic dependency is simply
         * absent from the list), so the caller still sees it and can reject it with its own code
         * and message.
         */
        val order: List<String>,
        /** Every detected cycle as a closed ring: the first id is repeated as the last one. */
        val cycles: List<List<String>>,
    ) {
        /** Ids that belong to at least one cycle; the host must refuse them. */
        val cyclicIds: Set<String> get() = cycles.flatMapTo(linkedSetOf()) { it }

        /** [cycles] rendered for a message, e.g. `a → b → a`; stable for a given graph. */
        val cycleRings: List<String> get() = cycles.map { it.joinToString(" → ") }

        val hasCycles: Boolean get() = cycles.isNotEmpty()
    }

    /**
     * Resolves [declared], mapping each plugin id to the ids it links against.
     *
     * Dependencies naming an id the host does not have cannot constrain the order and are ignored
     * here: the validator reports them as `DEPENDENCY_MISSING`. Input order does not matter - the
     * traversal walks ids and edges in sorted order, so the result is stable for a given graph.
     */
    fun resolve(declared: Map<String, Collection<String>>): Resolution {
        if (declared.isEmpty()) return Resolution(order = emptyList(), cycles = emptyList())

        val known = declared.keys
        val edges: Map<String, List<String>> = declared.mapValues { (_, dependencies) ->
            dependencies.asSequence().filter(known::contains).distinct().sorted().toList()
        }

        val marks = HashMap<String, Mark>()
        val finished = ArrayList<String>(known.size)
        val cycles = ArrayList<List<String>>()
        val path = ArrayList<String>()

        // Iterative DFS: the graph is caller-controlled, so recursion depth would be too. Finishing
        // a node after its dependencies is exactly the order the host needs.
        for (root in known.sorted()) {
            if (root in marks) continue
            marks[root] = Mark.ON_PATH
            path += root
            val stack = ArrayDeque<Frame>()
            stack.addLast(Frame(root, edges.getValue(root)))
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                if (frame.next < frame.edges.size) {
                    val dependency = frame.edges[frame.next++]
                    when (marks[dependency]) {
                        null -> {
                            marks[dependency] = Mark.ON_PATH
                            path += dependency
                            stack.addLast(Frame(dependency, edges.getValue(dependency)))
                        }
                        // Back edge: the dependency is still being visited, so the segment of the
                        // current path that starts there is a cycle.
                        Mark.ON_PATH -> cycles += ringEndingAt(path, dependency)
                        Mark.DONE -> Unit
                    }
                } else {
                    marks[frame.id] = Mark.DONE
                    finished += frame.id
                    path.removeAt(path.lastIndex)
                    stack.removeLast()
                }
            }
        }

        val cyclic = cycles.flatMapTo(linkedSetOf()) { it }
        return Resolution(order = finished.filter { it !in cyclic }, cycles = cycles)
    }

    /** The segment of the current DFS path that begins at [repeated], closed by repeating it. */
    private fun ringEndingAt(path: List<String>, repeated: String): List<String> {
        val start = path.indexOf(repeated)
        if (start < 0) return listOf(repeated, repeated)
        return path.subList(start, path.lastIndex + 1).toList() + repeated
    }

    private enum class Mark {
        /** Discovered and still on the DFS path: reaching it again is a cycle. */
        ON_PATH,

        /** Fully visited; its dependencies are already in the result. */
        DONE,
    }

    private class Frame(val id: String, val edges: List<String>) {
        var next: Int = 0
    }
}
