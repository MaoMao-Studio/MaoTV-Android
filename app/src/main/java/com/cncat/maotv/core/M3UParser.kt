package com.cncat.maotv.core

object M3UParser {
    private val attrRegex = Regex("([A-Za-z0-9_\\-]+)=\"([^\"]*)\"")

    fun parse(rawText: String): List<Channel> {
        val text = rawText.removePrefix("﻿")
        val result = ArrayList<Channel>()
        val groupCounter = HashMap<String, Int>()

        var pendingAttrs: Map<String, String>? = null
        var pendingName = ""
        var pendingGroupOverride: String? = null

        for (rawLine in text.split('\n', '\r')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF")) {
                val (attrs, name) = parseExtinf(line)
                pendingAttrs = attrs
                pendingName = name
                pendingGroupOverride = null
            } else if (line.startsWith("#EXTGRP:")) {
                pendingGroupOverride = line.substring(8).trim()
            } else if (line.startsWith("#")) {
                continue
            } else {
                val attrs = pendingAttrs ?: continue
                var group = (attrs["group-title"] ?: "").trim()
                if (group.isEmpty()) group = pendingGroupOverride ?: ""
                if (group.isEmpty()) group = "未分类"

                val tvgName = attrs["tvg-name"] ?: ""
                var name = pendingName.trim()
                if (name.isEmpty()) name = tvgName
                if (name.isEmpty()) name = "频道 ${result.size + 1}"

                val index = groupCounter[group] ?: 0
                groupCounter[group] = index + 1
                val number = attrs["tvg-chno"]?.trim()?.toIntOrNull() ?: (101 + index)
                val logo = attrs["tvg-logo"]?.trim()?.takeIf { it.isNotEmpty() }

                result.add(
                    Channel(
                        id = result.size, number = number, name = name, group = group, logo = logo,
                        url = line, tvgId = attrs["tvg-id"] ?: "", tvgName = tvgName,
                        tvId = (attrs["tv-id"] ?: "").trim(),
                    )
                )
                pendingAttrs = null
            }
        }
        return result
    }

    private fun parseExtinf(line: String): Pair<Map<String, String>, String> {
        val attrs = HashMap<String, String>()
        for (m in attrRegex.findAll(line)) {
            attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
        }
        // 频道名：最后一个引号之后的第一个逗号之后（没有引号就取第一个逗号）
        val lastQuote = line.lastIndexOf('"')
        val searchStart = if (lastQuote >= 0) lastQuote + 1 else 0
        var name = ""
        val comma = line.indexOf(',', searchStart)
        if (comma >= 0) name = line.substring(comma + 1)
        return attrs to name
    }
}
