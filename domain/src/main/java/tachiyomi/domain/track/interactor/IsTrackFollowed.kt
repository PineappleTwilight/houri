package tachiyomi.domain.track.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.track.model.Track

@Inject
class IsTrackFollowed {

    fun await(track: Track) =
        // TrackManager.MDLIST
        track.trackerId == 60L &&
            // FollowStatus.FOLLOWED (inverted from UNFOLLOWED)
            track.status != 0L
}
