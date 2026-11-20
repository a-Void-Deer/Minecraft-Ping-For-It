package nx.pingwheel.common.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.ConfigHandler;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.screen.SettingsScreen;
import org.apache.logging.log4j.util.TriConsumer;

import java.util.Objects;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.Global.CLIENT_COMMAND_ROOT;
import static nx.pingwheel.common.config.ClientConfig.MAX_CHANNEL_LENGTH;

public class ClientCommandBuilder {
	private ClientCommandBuilder() {}

	public static <S> LiteralArgumentBuilder<S> build(TriConsumer<CommandContext<S>, Boolean, MutableComponent> responseHandler) {
		return build(responseHandler, ClientConfig.HANDLER);
	}

	/** Builds the client command tree against the supplied client configuration handler. */
	public static <S> LiteralArgumentBuilder<S> build(
		TriConsumer<CommandContext<S>, Boolean, MutableComponent> responseHandler,
		ConfigHandler<ClientConfig> configHandler
	) {
		var langConfig = LanguageUtils.command("config");
		var langChannel = LanguageUtils.command("channel");

		var cmdChannel = LiteralArgumentBuilder.<S>literal("channel")
			.executes((context) -> {
				var currentChannel = Objects.requireNonNullElse(configHandler.getConfig().getChannel(), "");

				if (currentChannel.isEmpty()) {
					responseHandler.accept(context, true, langChannel.path("get", "response", "default").get());
				} else {
					responseHandler.accept(context, true, langChannel.path("get", "response")
						.get(Component.literal(currentChannel).withStyle(ChatFormatting.YELLOW))
					);
				}

				return 1;
			})
			.then(RequiredArgumentBuilder.<S, String>argument("channel_name", StringArgumentType.string()).executes((context) -> {
				var newChannel = context.getArgument("channel_name", String.class);

				if (newChannel.length() > MAX_CHANNEL_LENGTH) {
					responseHandler.accept(context, false, langChannel.path("set", "reject").get(MAX_CHANNEL_LENGTH));
					return 0;
				}

				// The handler rejects the edit before it runs when the file is
				// protected, and keeps the normal save result for write failures.
				if (!configHandler.editAndSaveIfWritable(config -> config.setChannel(newChannel))) {
					responseHandler.accept(context, false, langChannel.path("set", "failure").get());
					return 0;
				}

				if (newChannel.isEmpty()) {
					responseHandler.accept(context, true, langChannel.path("set", "response", "default").get());
				} else {
					responseHandler.accept(context, true, langChannel.path("set", "response")
						.get(Component.literal(newChannel).withStyle(ChatFormatting.YELLOW))
					);
				}

				return 1;
			}));

		var cmdConfig = LiteralArgumentBuilder.<S>literal("config")
			.executes((context) -> {
				Game.tell(() -> Game.setScreen(new SettingsScreen()));
				return 1;
			});

		Command<S> helpCallback = (context) -> {
			responseHandler.accept(context, true, LanguageUtils.join(
				Component.empty(),
				Component.literal("/" + CLIENT_COMMAND_ROOT + " config"),
				langConfig.path("description").wrapped().withStyle(ChatFormatting.GRAY),
				Component.literal("/" + CLIENT_COMMAND_ROOT + " channel"),
				langChannel.path("get", "description").wrapped().withStyle(ChatFormatting.GRAY),
				Component.literal("/" + CLIENT_COMMAND_ROOT + " channel <channel_name>"),
				langChannel.path("set", "description").wrapped().withStyle(ChatFormatting.GRAY)
			));
			return 1;
		};

		var cmdHelp = LiteralArgumentBuilder.<S>literal("help")
			.executes(helpCallback);

		return LiteralArgumentBuilder.<S>literal(CLIENT_COMMAND_ROOT)
			.executes(helpCallback)
			.then(cmdHelp)
			.then(cmdConfig)
			.then(cmdChannel);
	}
}
