package com.ramatafu.trafficmonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedAppEntity(
    @PrimaryKey val packageName: String
)

@Entity(tableName = "known_domains", primaryKeys = ["ip", "port"])
data class KnownDomainEntity(
    val ip: String,
    val port: Int,
    val domain: String
)

@Entity(tableName = "blocked_domains")
data class BlockedDomainEntity(
    @PrimaryKey val domain: String
)
