<path>F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric\docs\reviews\.scratch\net\minecraft\client\server\IntegratedServer.java</path>
<type>file</type>
<content>
			new NotificationManager()
		);
		this.setSingleplayerProfile(minecraft.getGameProfile());
		this.setDemo(minecraft.isDemo());
		this.setPlayerList(new IntegratedPlayerList(this, this.registries(), this.playerDataStorage));
		this.minecraft = minecraft;
	}

	protected boolean initServer() {
		LOGGER.info("Starting integrated minecraft server version {}", SharedConstants.getCurrentVersion().name());
		this.setUsesAuthentication(true);
		this.initializeKeyPair();
		this.loadLevel();
		GameProfile host = this.getSingleplayerProfile();
		String levelName = this.getWorldData().getLevelName();
		this.setMotd(host != null ? host.name() + " - " + levelName : levelName);
		this.saveEverything(false, true, true);
		return true;
	}

	public boolean isPaused() {
		return this.paused;
	}

	protected void processPacketsAndTick(final boolean sprinting) {
		TemporaryCollection ignored = Gizmos.withCollector(this.gizmoCollector);

		try {
			super.processPacketsAndTick(sprinting);
		} catch (Throwable var6) {
			if (ignored != null) {
				try {
					ignored.close();
				} catch (Throwable var5) {
					var6.addSuppressed(var5);
				}
			}

			throw var6;
		}

(Showing lines 96-135 of 534. Use offset=136 to continue.)
</content>