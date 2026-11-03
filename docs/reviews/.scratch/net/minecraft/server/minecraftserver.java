<path>F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric\docs\reviews\.scratch\net\minecraft\server\MinecraftServer.java</path>
<type>file</type>
<content>
		this.packetProcessor = new PacketProcessor(serverThread);
		this.clockManager = this.getDataStorage().computeIfAbsent(ServerClockManager.TYPE);
		this.clockManager.init(this);
		this.customBossEvents = this.savedDataStorage.computeIfAbsent(CustomBossEvents.TYPE);
		this.scheduledEvents = this.savedDataStorage.computeIfAbsent(TimerQueue.TYPE);
	}

	protected abstract boolean initServer() throws IOException;

	public ChunkLoadStatusView createChunkLoadStatusView(final int radius) {
		return new ChunkLoadStatusView() {
			private @Nullable ChunkMap chunkMap;
			private int centerChunkX;
			private int centerChunkZ;

			@Override
			public void moveTo(final ResourceKey<Level> dimension, final ChunkPos centerChunk) {
				ServerLevel level = MinecraftServer.this.getLevel(dimension);
				this.chunkMap = level != null ? level.getChunkSource().chunkMap : null;
				this.centerChunkX = centerChunk.x();
				this.centerChunkZ = centerChunk.z();
			}

			@Override
			public @Nullable ChunkStatus get(final int x, final int z) {
				return this.chunkMap == null ? null : this.chunkMap.getLatestStatus(ChunkPos.pack(x + this.centerChunkX - radius, z + this.centerChunkZ - radius));
			}

			@Override
			public int radius() {
				return radius;
			}
		};
	}

	protected void loadLevel() {
		boolean startedWorldLoadProfiling = !JvmProfiler.INSTANCE.isRunning()
			&& SharedConstants.DEBUG_JFR_PROFILING_ENABLE_LEVEL_LOADING
			&& JvmProfiler.INSTANCE.start(Environment.from(this));
		ProfiledDuration profiledDuration = JvmProfiler.INSTANCE.onWorldLoadedStarted();
		this.worldData.setModdedInfo(this.getServerModName(), this.getModdedStatus().shouldReportAsModified());
		this.createLevels();
		this.forceDifficulty();
		this.prepareLevels();
		if (profiledDuration != null) {
			profiledDuration.finish(true);
		}

		if (startedWorldLoadProfiling) {
			try {
				JvmProfiler.INSTANCE.stop();
			} catch (Throwable t) {
				LOGGER.warn("Failed to stop JFR profiling", t);
			}
		}
	}

	protected void forceDifficulty() {
	}

	protected void createLevels() {
		ServerLevelData levelData = this.worldData.overworldData();
		boolean isDebug = this.worldData.isDebugWorld();
		Registry<LevelStem> dimensions = this.registries.compositeAccess().lookupOrThrow(Registries.LEVEL_STEM);
		WorldOptions worldOptions = this.worldGenSettings.options();
		long seed = worldOptions.seed();
		long biomeZoomSeed = BiomeManager.obfuscateSeed(seed);
		List<CustomSpawner> overworldCustomSpawners = ImmutableList.of(
			new PhantomSpawner(), new PatrolSpawner(), new CatSpawner(), new VillageSiege(), new WanderingTraderSpawner(this.savedDataStorage)
		);

(Showing lines 360-429 of 2277. Use offset=430 to continue.)
</content>