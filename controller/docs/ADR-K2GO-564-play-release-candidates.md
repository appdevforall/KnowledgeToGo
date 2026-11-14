# ADR-K2GO-564 : Play release candidates, then publish the approved build

- Status: Proposed
- Date: 2026-11-13
- Ticket: K2GO-564 (parent K2GO-7; relates to K2GO-500, K2GO-457, K2GO-433)
- Author: AppDevForAll

## Decision in one line

A release goes to Google review first, as a candidate (`vX.Y.Z-rc.N`). When Google approves
a candidate, the final release is built from that code. Each build names itself: a candidate
says `vX.Y.Z-rc.N`, and only a build that says `vX.Y.Z` can become the release.

## Context (facts)

- Google can reject an update even when our code did not change, because Google updates its
  policies and checks. We must learn this before we announce a release on GitHub.
- The Play Developer API can upload an AAB, put it on a track and send it to review.
  It cannot read the review result, and it cannot press "Publish changes" after an approval.
  Google answers by email and in Play Console.
  ([issue 179708468](https://issuetracker.google.com/issues/179708468))
- Fast pre-review checks run during the API call, so some rejections come back at once.
  Passing them does not guarantee passing review.
  ([Play Console Help](https://support.google.com/googleplay/android-developer/answer/14807773))
- By default a new commit cancels a review in progress (`CANCEL_IN_REVIEW_AND_SUBMIT`).
  ([edits.commit](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit))
- Play rejects a versionCode that it has seen. Play already holds codes from builds of `main`
  (K2GO-457), so the tag `v1.1.0` (code 64) cannot be uploaded again.
- With managed publishing on, an approved change waits in Play Console until a person
  publishes it.

## Decision

1. Versions follow semver. `versionName` in `build.gradle` is the full label of the build:
   `v1.2.0-rc.N` for a candidate, `v1.2.0` for the final release. The same label names the git
   tag, the Play release and the R2 files, so a binary always says what it is.
   A rejected candidate was never released, so the version stays the same and the next
   candidate is `rc.N+1`. A change after `vX.Y.Z` is released is a new version (`vX.Y.Z+1`).
   ([semver items 3 and 9](https://semver.org/#spec-item-9))
2. Button 1, `play-candidate.yml`: `versionName` must be the next candidate of an unreleased
   version. Build the Play AAB, take versionCode = highest code that Play knows + 1, send to
   review with `ERROR_IF_IN_REVIEW`, tag the commit with its `versionName`, and archive the AAB
   to R2. The rc tag is pushed only after Play accepts the upload. It runs only from `main`.
   A run on the internal track (no formal review) gets no rc tag.
3. Google approves or rejects (email, Play Console). A person reads the verdict.
4. Button 2, `play-release.yml`: accept only the newest rc of the version (each new candidate
   replaces the older one in Play) and run the release build (GitHub Release, website APKs, R2,
   OTA `update.json`). An rc build is never released as final: both the tag step and the
   release build refuse a commit whose `versionName` is not exactly `vX.Y.Z`. The final build
   (versionName `vX.Y.Z`, sent to Play, then tagged) is not designed yet, so today button 2
   refuses every rc commit. The release build runs only when `play-release.yml` calls it:
   a hand-pushed `v*` tag does not start a release.
5. Play credentials: a Google Cloud service account with "Release apps to testing tracks" on
   this app only. Its JSON key is the secret `PLAY_SERVICE_ACCOUNT_JSON` of the GitHub
   environment `google-play`, which asks a repo admin to approve each run.

## Consequences

- Every binary shows its real label (Settings > About, file names), so an rc build is never
  mistaken for the release. The final build is a new versionCode, so Google reviews it again.
- A rejection costs a new candidate, not a new version number.
- The Play versionCode comes from Play, so it can differ from `versionCode * 10` in
  `build.gradle`. The website APKs keep the `build.gradle` codes. The two channels have
  different signers and cannot update each other, so the codes do not conflict.
- `android-r2-republish.yml` stays as the manual fallback until the first candidate run works.
  Its input `play_version_code` takes the highest code in Play Console + 1.
  Then its Play AAB part is removed.
