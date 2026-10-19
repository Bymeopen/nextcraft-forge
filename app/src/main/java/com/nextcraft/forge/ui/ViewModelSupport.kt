package com.nextcraft.forge.ui

internal fun shellSingleQuote(raw: String): String = "'" + raw.replace("'", "'\"'\"'") + "'"
