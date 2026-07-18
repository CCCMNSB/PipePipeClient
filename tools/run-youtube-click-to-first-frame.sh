#!/usr/bin/env bash
set -euo pipefail

url="${1:-https://www.youtube.com/watch?v=G-eNlqqkn1w}"
repetitions="${REPETITIONS:-5}"
detail_timeout_seconds="${DETAIL_TIMEOUT_SECONDS:-90}"
frame_timeout_seconds="${FRAME_TIMEOUT_SECONDS:-30}"
build_install="${BUILD_INSTALL:-true}"
readonly adb_command="${ADB:-adb}"
output="${OUTPUT:-../log/youtube-click-to-first-frame-$(date +%Y%m%d-%H%M%S).log}"
jsonl="${JSONL_OUTPUT:-${output%.log}.jsonl}"

mkdir -p "$(dirname "$output")" "$(dirname "$jsonl")"

case "${build_install,,}" in
  1|true|yes) build_install=true ;;
  0|false|no) build_install=false ;;
  *) echo "BUILD_INSTALL must be true or false" >&2; exit 2 ;;
esac

if [[ "$build_install" == true ]]; then
  ./gradlew assembleDebug
fi

abi="$($adb_command shell getprop ro.product.cpu.abi | tr -d '\r')"
app_apk="$(find app/build/outputs/apk/debug -name "*-${abi}-debug.apk" -print -quit)"
app_metadata="app/build/outputs/apk/debug/output-metadata.json"
app_id="$(sed -n 's/.*"applicationId": "\([^"]*\)".*/\1/p' "$app_metadata" | head -1)"
if [[ -z "$app_apk" || -z "$app_id" ]]; then
  echo "Could not locate the debug APK or application ID" >&2
  exit 2
fi
if [[ "$build_install" == true ]]; then
  $adb_command install -r "$app_apk" >/dev/null
fi

dump_ui() {
  $adb_command exec-out uiautomator dump /dev/tty 2>/dev/null \
    | tr -d '\r\n'
}

detail_bounds() {
  sed -nE 's/.*resource-id="[^"]*:id\/detail_thumbnail_root_layout"[^>]*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p'
}

show_info_bounds() {
  sed -nE 's/.*text="Show info"[^>]*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p'
}

just_once_bounds() {
  sed -nE 's/.*resource-id="android:id\/button2"[^>]*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p'
}

known_dialog_bounds() {
  local input
  local button_id
  input="$(cat)"
  if printf '%s' "$input" | rg -q 'text="Enable update checker"|text="Support the Project"'; then
    button_id="button2"
  elif printf '%s' "$input" | rg -q 'text="Announcement"|text="What.s New"'; then
    button_id="button1"
  else
    return
  fi
  printf '%s' "$input" \
    | sed -nE "s/.*resource-id=\"android:id\\/${button_id}\"[^>]*bounds=\"\\[([0-9]+),([0-9]+)\\]\\[([0-9]+),([0-9]+)\\]\".*/\\1 \\2 \\3 \\4/p"
}

tap_bounds() {
  local left top right bottom
  read -r left top right bottom <<< "$1"
  $adb_command shell input tap "$(((left + right) / 2))" "$(((top + bottom) / 2))"
}

$adb_command logcat -c
: > "$output"
: > "$jsonl"

for ((round=0; round<repetitions; round++)); do
  $adb_command shell am force-stop "$app_id"
  $adb_command shell am start -W -a android.intent.action.VIEW -d "$url" "$app_id" \
    | tee -a "$output"

  bounds=""
  for ((waited=0; waited<detail_timeout_seconds; waited++)); do
    ui="$(dump_ui || true)"
    dialog_confirm="$(printf '%s' "$ui" | known_dialog_bounds || true)"
    if [[ -n "$dialog_confirm" ]]; then
      tap_bounds "$dialog_confirm"
      sleep 0.25
      continue
    fi
    router_choice="$(printf '%s' "$ui" | show_info_bounds || true)"
    if [[ -n "$router_choice" ]]; then
      tap_bounds "$router_choice"
      sleep 0.25
      ui="$(dump_ui || true)"
      router_confirm="$(printf '%s' "$ui" | just_once_bounds || true)"
      [[ -n "$router_confirm" ]] && tap_bounds "$router_confirm"
      sleep 0.25
      continue
    fi
    bounds="$(printf '%s' "$ui" | detail_bounds || true)"
    [[ -n "$bounds" ]] && break
    sleep 1
  done
  if [[ -z "$bounds" ]]; then
    echo "Round $round: detail play target did not appear" | tee -a "$output" >&2
    $adb_command exec-out uiautomator dump /dev/tty 2>/dev/null >> "$output" || true
    exit 1
  fi

  before_count="$($adb_command logcat -d -v raw -s PlaybackStartup:I '*:S' \
    | rg -c '"record":"click_to_first_frame"' || true)"
  tap_bounds "$bounds"

  summary=""
  for ((waited=0; waited<frame_timeout_seconds * 4; waited++)); do
    summaries="$($adb_command logcat -d -v raw -s PlaybackStartup:I '*:S' \
      | rg '"record":"click_to_first_frame"' || true)"
    after_count="$(printf '%s\n' "$summaries" | sed '/^$/d' | wc -l)"
    if ((after_count > before_count)); then
      summary="$(printf '%s\n' "$summaries" | tail -1 \
        | sed -E 's/^.*PIPEPIPE_PLAYBACK_STARTUP (\{.*\})$/\1/')"
      break
    fi
    sleep 0.25
  done
  if [[ -z "$summary" ]]; then
    echo "Round $round: first frame timed out" | tee -a "$output" >&2
    $adb_command logcat -d -v threadtime -s PlaybackStartup:I SabrSessionStore:I SabrLocalDomPoToken:I \
      >> "$output"
    exit 1
  fi
  printf '%s\n' "$summary" | tee -a "$output" "$jsonl"
done

$adb_command logcat -d -v threadtime \
  | rg 'PIPEPIPE_PLAYBACK_STARTUP|SabrSessionStore|SabrLocalDomPoToken' \
  >> "$output" || true

echo "Click-to-first-frame results: $jsonl"
