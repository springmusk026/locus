package dev.locus.core.testing

import dev.locus.core.linux.LinuxEnvironment

/**
 * Makes Linux count as ready without a real shell behind it, for view model tests whose commands go
 * through a fake source. A command that reaches it fails the test instead of starting a host process.
 */
fun LinuxEnvironment.readyWithoutShell() = runOnHost { command, _ ->
    throw AssertionError("This test runs no commands, but asked for: $command")
}
