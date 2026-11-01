#!/bin/sh
# One-shot setup on the board. Run from ~/deskmatrix: sudo sh install.sh
set -e
timedatectl set-timezone Asia/Kolkata
cp /home/arduino/deskmatrix/deskmatrixd.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now deskmatrixd
systemctl --no-pager status deskmatrixd | head -5
