package tachiyomi.domain.track.interactor

import tachiyomi.domain.track.model.Track

class IsTrackFollowed {

    fun await(track: Track) =
        // TrackManager.MDLIST
        track.trackerId == 60L &&
            // FollowStatus.FOLLOWED (inverted from UNFOLLOWED)
            track.status != 0L
}
