#!/bin/sh
# Copies notes/*.txt into backup/ and prints how many were copied.
mkdir backup
count=0
for f in $(ls notes/*.txt); do
  cp $f backup/
  count=$((count + 1))
done
echo "copied $count files"
