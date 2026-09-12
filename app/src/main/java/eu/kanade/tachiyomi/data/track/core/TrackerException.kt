package eu.kanade.tachiyomi.data.track.core

sealed class TrackerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthenticationFailed(trackerId: Long, cause: Throwable? = null) :
        TrackerException("Authentication failed for tracker $trackerId", cause)

    class NotFound(trackerId: Long, remoteId: Long) :
        TrackerException("Entry $remoteId not found on tracker $trackerId")

    class RateLimited(trackerId: Long, cause: Throwable? = null) :
        TrackerException("Rate limited on tracker $trackerId", cause)

    class NetworkError(trackerId: Long, cause: Throwable) :
        TrackerException("Network error on tracker $trackerId: ${cause.message}", cause)

    class InvalidCredentials(trackerId: Long) :
        TrackerException("Invalid credentials for tracker $trackerId")

    class UnsupportedOperation(trackerId: Long, op: String) :
        TrackerException("Unsupported operation $op on tracker $trackerId")
}
