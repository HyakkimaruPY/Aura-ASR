# Current release — Live channel synchronization corrections (v0.7.6)

This document describes the current code snapshot only, not old build histories.

## Observed symptoms

- Channel HLS without `EXT-X-PROGRAM-DATE-TIME`: visible player and silent scout have different segment positions and no absolute common clock.
- Decoder PCM timestamps and player start time can refer to different points in the stream.
- Scout audio racing over a minute ahead of the player created a growing untranslated cue backlog.
- Overlapping candidate cues could hide a translated subtitle still valid at the current timeline position.
- `ERROR_CODE_BEHIND_LIVE_WINDOW` was not fully recovered in the main player.

## Current fixes to evaluate on devices

1. LiveSubtitleClock uses relative monotonic fallback when program-date-time is absent; honors epoch clock when available.
2. PcmTap calibrates audio against scout player clock and resets on discontinuity/flush.
3. SubtitleTimeline.atLive selects eligible translated cues despite overlapping ASR candidates.
4. Live backpressure uses hysteresis and catches out-of-window startup; avoids unbounded scout acceleration.
5. Player listeners recover live-window errors without stopping subtitle recognition.
6. At least two translation workers keep near-live captions moving; remote API is now optional and explicitly configured.

## Testing limitations

Live HLS variant alignment without program date time cannot have guaranteed absolute accuracy. Validate in a real stream and device; this snapshot's full Android build and performance benchmarks are not independently certified.
