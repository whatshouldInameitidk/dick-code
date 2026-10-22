package top.niunaijun.blackboxa.auth

object AuthSession {
    @Volatile
    var isAuthorized: Boolean = false
        private set

    fun authorize() {
        isAuthorized = true
    }

    fun clear() {
        isAuthorized = false
    }
}
