package com.fantamomo.hc.dns.model

enum class SlackUserIdFoundState {
    /**
     * The Slack user id was found and we set it in the db
     */
    FOUND,

    /**
     * We tried to find a slack id but it was not found
     */
    NOT_FOUND,

    /**
     * The slack id was resolved via the `data/overriden_github_id_to_slack_id.json` file,
     * we have written it to the db
     */
    OVERRIDDEN,

    /**
     * Default value in database, if we find users with this state, we are trying to find their slack id
     */
    UNKNOWN
}