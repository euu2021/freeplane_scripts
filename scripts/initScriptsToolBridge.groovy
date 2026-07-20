// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: MIT

// Bridge to run initScriptsTool.groovy at startup.
// Put a copy of this file in your Freeplane profile's `scripts/init/` folder:
// Freeplane runs init scripts on startup, and this one evaluates the shared
// initScriptsTool, which then runs every `//init` script across the script folders.
// Adjust the path below to wherever initScriptsTool.groovy lives on your machine.
// Example (replace with your real path):
evaluate(new File("/path/to/FP/Scripts/compartilhados/scripts/initScriptsTool.groovy"))
