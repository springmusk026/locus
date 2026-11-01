#!/bin/sh
#
# Manual on-device check that Linux programs can't reach Locus's own process through /proc
# (docs/design/linux-isolation.md). Run it INSIDE Locus's Linux, e.g. copy it into the workspace
# and ask the agent to run `sh /workspace/verify-linux-isolation.sh`, or, on a debug build:
#
#   adb shell run-as dev.locus.debug sh -c 'cat > files/workspace/verify-linux-isolation.sh' < tools/verify-linux-isolation.sh
#
# then have the agent run it. It prints PASS/FAIL lines and exits non-zero on any FAIL.
#
# The kernel-level layer (the app process is non-dumpable) can also be checked from the host,
# as the app's uid, without Linux at all; expect "Permission denied" and owner root:
#
#   adb shell 'pid=$(pidof dev.locus.debug); run-as dev.locus.debug ls /proc/$pid/fd; run-as dev.locus.debug stat -c %U /proc/$pid/fd'
#
# Uses only BusyBox tools from Alpine's minirootfs.

fails=0
pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; fails=$((fails + 1)); }
ppid_of() { awk '/^PPid:/ { print $2 }' "/proc/$1/status" 2>/dev/null; }

# Walk up from this shell to proot (its command line names libproot.so); its parent is Locus.
pid=$$
proot=""
while [ -n "$pid" ] && [ "$pid" -gt 1 ]; do
    if tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q 'libproot'; then proot=$pid; break; fi
    pid=$(ppid_of "$pid")
done
if [ -z "$proot" ]; then
    echo "Couldn't find proot among this shell's parents; is this running inside Locus's Linux?"
    exit 2
fi
app=$(ppid_of "$proot")
echo "proot pid $proot, Locus pid $app"

# 1. Locus's own /proc entry is an empty directory (the bind over /proc/<pid>).
if [ -z "$(ls -A "/proc/$app" 2>/dev/null)" ]; then pass "/proc/$app is empty"; else fail "/proc/$app lists: $(ls "/proc/$app" | head -5 | tr '\n' ' ')"; fi

# 2. None of the dangerous entries resolve, for the process or through task/<tid>.
for entry in fd fd/0 fd/3 cwd root exe map_files mem environ maps task "task/$app/fd" "task/$app/mem"; do
    p="/proc/$app/$entry"
    if [ -e "$p" ] || [ -L "$p" ] || readlink "$p" > /dev/null 2>&1 || head -c 1 "$p" > /dev/null 2>&1; then
        fail "$p is reachable"
    else
        pass "$p is unreachable"
    fi
done

# 3. Threads started after this command (not hidden by the bind) must still be closed: that's the
#    non-dumpable layer. Try every id near Locus's for open files under its private data.
leaks=0
n=$app
end=$((app + 1500))
while [ "$n" -le "$end" ]; do
    if [ "$n" != "$proot" ] && [ -d "/proc/$n/fd" ] 2>/dev/null; then
        if ls -l "/proc/$n/fd/" 2>/dev/null | grep -Eq 'databases/|datastore/|shared_prefs/|agent-home/'; then
            fail "/proc/$n/fd shows Locus's private files"
            leaks=$((leaks + 1))
        fi
        if head -c 1 "/proc/$n/mem" > /dev/null 2>&1 && tr '\0' ' ' < "/proc/$n/cmdline" 2>/dev/null | grep -q '^dev\.locus'; then
            fail "/proc/$n/mem of a Locus thread is readable"
            leaks=$((leaks + 1))
        fi
    fi
    n=$((n + 1))
done
[ "$leaks" -eq 0 ] && pass "no id from $app to $end exposes Locus's files or memory"

# 4. The database, settings and keyset can't be named directly either.
for f in /data/data/dev.locus/databases/locus.db /data/data/dev.locus.debug/databases/locus.db \
         /data/user/0/dev.locus/files/datastore/settings.json /data/user/0/dev.locus.debug/files/datastore/settings.json; do
    if head -c 1 "$f" > /dev/null 2>&1; then fail "$f is readable"; else pass "$f is not readable"; fi
done

# 5. Binder is hidden, so Linux programs can't call Android services (the Keystore) as Locus.
for d in /dev/binder /dev/hwbinder /dev/vndbinder; do
    if [ -c "$d" ]; then fail "$d is a character device"; else pass "$d is not a binder device"; fi
done

# 6. Ordinary things still work.
[ -n "$(ls /proc/self/fd 2>/dev/null)" ] && pass "/proc/self/fd works" || fail "/proc/self/fd is broken"
grep -q . /proc/cpuinfo 2>/dev/null && pass "/proc/cpuinfo works" || fail "/proc/cpuinfo is broken"
ps > /dev/null 2>&1 && pass "ps works" || fail "ps is broken"
echo hi > /tmp/.locus-verify && rm /tmp/.locus-verify && pass "/tmp is writable" || fail "/tmp isn't writable"

echo
if [ "$fails" -eq 0 ]; then echo "All checks passed."; else echo "$fails check(s) failed."; fi
[ "$fails" -eq 0 ]
