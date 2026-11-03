<path>F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric\docs\reviews\.scratch\net\minecraft\server\dedicated\DedicatedServer.java</path>
<type>file</type>
<content>
		if (this.convertOldUsers()) {
			this.services.nameToIdCache().save();
		}

		if (!OldUsersConverter.areOldUserlistsRemoved()) {
			return false;
		}

		this.setPlayerList(new DedicatedPlayerList(this, this.registries(), this.playerDataStorage));
		this.tickTimeLogger = new RemoteSampleLogger(TpsDebugDimensions.values().length, this.debugSubscribers(), RemoteDebugSampleType.TICK_TIME);
		long levelNanoTime = Util.getNanos();
		this.services.nameToIdCache().resolveOfflineUsers(!this.usesAuthentication());
		LOGGER.info("Preparing level \"{}\"", this.getLevelIdName());
		this.loadLevel();
		long elapsed = Util.getNanos() - levelNanoTime;
		String time = String.format(Locale.ROOT, "%.3fs", elapsed / 1.0E9);
		LOGGER.info("Done ({})! For help, type \"help\"", time);
		if (properties.announcePlayerAchievements != null) {
			this.getGameRules().set(GameRules.SHOW_ADVANCEMENT_MESSAGES, properties.announcePlayerAchievements, this);
		}

		if (properties.enableQuery) {
			LOGGER.info("Starting GS4 status listener");
			this.queryThreadGs4 = QueryThreadGs4.create(this);
		}

		if (properties.enableRcon) {
			LOGGER.info("Starting remote control listener");
			this.rconThread = RconThread.create(this);
		}

(Showing lines 230-259 of 851. Use offset=260 to continue.)
</content>