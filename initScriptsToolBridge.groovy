// Bridge to run initScriptsTool.groovy at startup.
// Put a copy of this file in your Freeplane profile's `scripts/init/` folder:
// Freeplane runs init scripts on startup, and this one evaluates the shared
// initScriptsTool, which then runs every `//init` script across the script folders.
// Adjust the path below to wherever initScriptsTool.groovy lives on your machine.
evaluate(new File("C:/Users/kauen/OneDrive/FP/Scripts/compartilhados/initScriptsTool.groovy"))
