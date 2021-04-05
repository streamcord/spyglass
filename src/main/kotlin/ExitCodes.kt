package io.streamcord.spyglass

object ExitCodes {
    const val MISSING_ENV_VAR = 1
    const val MISSING_CONFIG_FILE = 2
    const val NO_TWITCH_ACCESS_TOKEN = 3
    const val INVALID_DB_CONNECTION_STRING = 4
    const val INVALID_DB_NAME = 5
    const val INVALID_DB_COLLECTION_NAME = 6
    const val AMQP_CONNECTION_FAILED = 7
    const val UNCAUGHT_ERROR = 255
    const val WORST_CASE_SCENARIO = 666
}
