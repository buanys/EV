package ev.buan.plugins.agent.api

/**
 * One step observed while a model or agent turn runs, in a vendor-neutral shape.
 *
 * Provider plugins map their wire format into these events, so a UI can render progress and an
 * agent can execute tool requests without ever seeing a vendor type. Events are carried by
 * [AgentReply.events] in the order they happened.
 */
data class AgentEvent(
    val kind: Kind,
    /** Text of a [Kind.TEXT] chunk, result of a [Kind.TOOL_RESULT], or explanation of an [Kind.ERROR]. */
    val text: String? = null,
    /** Tool name for [Kind.TOOL_CALL] and [Kind.TOOL_RESULT]. */
    val toolName: String? = null,
    /** Correlates a [Kind.TOOL_CALL] with the [Kind.TOOL_RESULT] that answers it. */
    val toolCallId: String? = null,
    /** Arguments of a [Kind.TOOL_CALL] as a JSON object in text form. */
    val argumentsJson: String? = null,
) {
    enum class Kind {
        /** Model output text, either a delta or a complete block. */
        TEXT,

        /** The model asked the caller to run a tool. */
        TOOL_CALL,

        /** A tool call finished; [text] carries its result. */
        TOOL_RESULT,

        /** A non-fatal problem; the turn may still produce a reply. */
        ERROR,
    }
}
