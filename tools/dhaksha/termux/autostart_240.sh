#!/data/data/com.termux/files/usr/bin/bash
# Termux:Boot: starts the 240p stream after the MK15 boots. Log: ~/stream_log.txt

termux-wake-lock
sleep 30
until ping -c 1 -W 2 192.168.144.25 > /dev/null 2>&1; do
  echo "$(date '+%F %T') waiting for camera" >> ~/stream_log.txt
  sleep 5
done
bash ~/stream_240.sh >> ~/stream_log.txt 2>&1
