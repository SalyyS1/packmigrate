package com.salyvn.packmigrate

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.versionOption
import com.salyvn.packmigrate.cli.MigrateCommand
import com.salyvn.packmigrate.core.BuildInfo

/**
 * Entry point. One command, because the tool does one thing: read the `overrides` a pack wrote for the
 * format that ended at 1.21.3, and write the item model definitions that 1.21.4 introduced, saying
 * exactly where the two do not line up.
 */
fun main(args: Array<String>) = MigrateCommand()
    .versionOption(BuildInfo.version, names = setOf("--version"))
    .main(args)
