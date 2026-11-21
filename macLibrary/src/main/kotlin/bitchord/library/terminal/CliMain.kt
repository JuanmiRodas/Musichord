package bitchord.library.terminal

import bitchord.library.cli

/** The jar's entry point: the command line alone, no window. */
fun main(args: Array<String>) = cli(if (args.isEmpty()) arrayOf("help") else args)
