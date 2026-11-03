<path>F:\Minecraft_Mod_Project\Minecraft_26.3\Minecraft_26.3_Fabric\docs\reviews\.scratch\net\minecraft\client\Minecraft.java</path>
<type>file</type>
<content>
552: 		this.windowSurface = device.createSurface(this.window.handle(), this.window::isIconified);
553: 		this.sdlEventHandler = new SDLEventHandler(this, this.window);
554: 		this.textInputManager = new TextInputManager(this.window);
555: 		this.sdlEventHandler.flushInputEvents();
556: 		this.window.setWindowCloseCallback(new Runnable() {
557: 			private boolean threadStarted;
558: 
559: 			@Override
560: 			public void run() {
561: 				if (!this.threadStarted) {
562: 					this.threadStarted = true;
563: 					ClientShutdownWatchdog.startShutdownWatchdog("window close callback", false, Minecraft.this, gameConfig, Minecraft.this.gameThread.threadId());
564: 				}
565: 			}
566: 		});
567: 		GameLoadTimesEvent.INSTANCE.endStep(TelemetryProperty.LOAD_TIME_PRE_WINDOW_MS);
568: 		this.mouseHandler = new MouseHandler(this);
569: 		this.keyboardHandler = new KeyboardHandler(this);

(Showing lines 552-569 of 3104. Use offset=570 to continue.)
</content>