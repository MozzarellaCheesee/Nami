package dev.nami.core.database

const val CREATE_SEARCH_INDEX_SQL =
    "CREATE VIRTUAL TABLE IF NOT EXISTS search_index USING fts5(" +
        "itemId UNINDEXED, type UNINDEXED, title, subtitle, format UNINDEXED, year UNINDEXED)"
