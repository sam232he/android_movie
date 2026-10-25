    when {
        facts.internalStorage -> {
            writability = Writability.UNCONFIRMED
            warnings += DestinationWarning(
                DestinationWarningCode.INTERNAL_STORAGE,
                "Internal storage is not a valid destination for exports.",
                blocking = true,
            )
        }
        facts.permissionRevoked || (!facts.grantedRead && !facts.grantedWrite) -> {
            writability = Writability.UNCONFIRMED
            warnings += DestinationWarning(
                DestinationWarningCode.PERMISSION_REVOKED,
                "Permission for ${facts.displayName} was revoked. Another destination will not be used.",
                blocking = true,
            )
        }
        !facts.volumePresent -> {
            writability = Writability.UNCONFIRMED
            warnings += DestinationWarning(
                DestinationWarningCode.DISCONNECTED,
                "${facts.displayName} is not connected. Another destination will not be used.",
                blocking = true,
            )
        }
        !facts.grantedWrite -> {
            writability = Writability.UNCONFIRMED
            warnings += DestinationWarning(
                DestinationWarningCode.NO_WRITE_PERMISSION,
                "This folder was opened without write access.",
                blocking = true,
            )
        }
        else -> {
            writability = decideWritability(facts, filesystem)
            warnings += writabilityWarnings(filesystem, writability)
            if (!facts.persisted) {
                warnings += if (facts.persistable) {
                    DestinationWarning(
                        DestinationWarningCode.PERMISSION_NOT_PERSISTED,
                        "Write access is granted for this session and still needs to be saved.",
                        blocking = false,
                    )
                } else {
                    DestinationWarning(
                        DestinationWarningCode.PERMISSION_NOT_PERSISTED,
                        "Write access was granted, but Android will not keep it. The folder has to be chosen again next time.",
                        blocking = false,
                    )
                }
            }
            warnings += fileWarnings(filesystem, selected)
            warnings += spaceWarnings(facts, selected)
        }
    }