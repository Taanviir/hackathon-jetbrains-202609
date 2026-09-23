#!/usr/bin/env bash
# Drive the sandbox IDE (WSLg) for screenshots: ui.sh key ctrl+shift+a | ui.sh type "text" | ui.sh shot out.png | ui.sh click X Y
X="env LD_LIBRARY_PATH=/tmp/claude-1000/-home-tanas-projects-hackathons-hackathon-jetbrains-202609/2f43853a-ff00-47ec-b667-7554ef5c4d44/scratchpad/xdo/root/usr/lib/x86_64-linux-gnu DISPLAY=:0 /tmp/claude-1000/-home-tanas-projects-hackathons-hackathon-jetbrains-202609/2f43853a-ff00-47ec-b667-7554ef5c4d44/scratchpad/xdo/root/usr/bin/xdotool"
WID=$(DISPLAY=:0 xwininfo -root -tree | grep -E '"koog' | head -1 | awk '{print $1}')
case "$1" in
  key)   $X windowactivate --sync $((WID)) key --clearmodifiers "$2" ;;
  type)  $X windowactivate --sync $((WID)) type --delay 15 "$2" ;;
  click) $X windowactivate --sync $((WID)) mousemove --window $((WID)) "$2" "$3" click 1 ;;
  shot)  DISPLAY=:0 import -window "$WID" "$2" ;;
esac
