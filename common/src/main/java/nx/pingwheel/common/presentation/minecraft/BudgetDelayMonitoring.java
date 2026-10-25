package nx.pingwheel.common.presentation.minecraft;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.presentation.budget.BudgetDelayMonitor;
import nx.pingwheel.common.presentation.budget.BudgetDelayMonitor.Count;
import nx.pingwheel.common.presentation.budget.BudgetDelayMonitor.CycleSummary;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayState;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Unit;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.WorkItem;

/** One monitoring lifetime per authoritative server identity, independent of config refreshes. */
public final class BudgetDelayMonitoring {

	private static final Coordinator<MinecraftServer> COORDINATOR = new Coordinator<>(new MinecraftPort());

	private BudgetDelayMonitoring() {}

	public static BudgetDelaySink sink(MinecraftServer server) { return COORDINATOR.sink(server); }
	public static void finishTick(MinecraftServer server) { COORDINATOR.finishTick(server); }
	public static void reset() { COORDINATOR.reset(); }
	public static void stopped(MinecraftServer server) { COORDINATOR.stopped(server); }

	/** No target context reaches the vanilla localized administrator notice. */
	static Component notice(Set<Module> modules) {
		MutableComponent list = Component.empty();
		boolean first = true;
		for (Module module : Module.values()) {
			if (!modules.contains(module)) continue;
			if (!first) list.append(", ");
			first = false;
			list.append(Component.translatable(switch (module) {
				case PRESENTATION -> "message.pingforit.budget_delay.module.presentation";
				case INVENTORY_PREVIEW -> "message.pingforit.budget_delay.module.inventory_preview";
				case INVENTORY_TRACKING -> "message.pingforit.budget_delay.module.inventory_tracking";
			}));
		}
		return Component.translatable("message.pingforit.budget_delay", list);
	}

	/** Each closed delayed cycle has already passed the monitor's domain frequency gate. */
	static void reportSummary(CycleSummary summary) {
		Diagnostics.domainApprovedEvent(Level.WARN, "budget/delay", summary.module().name(), "closed-cycle",
			() -> formatSummary(summary));
	}

	/** Unprefixed: the diagnostic backend owns the prefix. */
	static String formatSummary(CycleSummary summary) {
		StringBuilder line = new StringBuilder("budget delay module=").append(summary.module())
			.append(" cycle=").append(summary.cycleId()).append(" observed work items=")
			.append(count(summary.observedWorkItems())).append(" observed deferred quantity={");
		boolean first = true;
		for (var entry : summary.quantities().entrySet()) {
			if (!first) line.append(", ");
			first = false;
			line.append(unitLabel(entry.getKey())).append('=').append(count(entry.getValue()));
		}
		if (!first) line.append(", ");
		line.append("unknown observations=").append(count(summary.unknownQuantities())).append("} reasons={");
		first = true;
		for (var entry : summary.reasons().entrySet()) {
			if (!first) line.append(", ");
			first = false;
			line.append(entry.getKey()).append('=').append(count(entry.getValue()));
		}
		line.append("} context=[");
		first = true;
		for (WorkItem sample : summary.samples()) {
			if (!first) line.append("; ");
			first = false;
			line.append(sample.identity()).append(": ").append(sample.description());
		}
		long more = summary.observedWorkItems().amount() - summary.samples().size();
		if (more > 0 || summary.observedWorkItems().saturated()) {
			if (!first) line.append("; ");
			line.append("and ").append(summary.observedWorkItems().saturated() ? ">=" : "")
				.append(Math.max(0, more)).append(" more work items");
		}
		return line.append(']').toString();
	}

	private static String count(Count count) { return (count.saturated() ? ">=" : "") + count.amount(); }
	private static String unitLabel(Unit unit) {
		return switch (unit) {
			case PHYSICAL_SLOT -> "physical/decode slots";
			case LOGICAL_SLOT -> "subject-logical slots";
			default -> unit.name();
		};
	}

	interface Recipient {
		UUID id();
		boolean hasPermissions(int level);
		void sendSystemMessage(Set<Module> modules);
	}
	interface ServerPort<S> {
		long serverTick(S server);
		boolean notificationsEnabled(S server);
		boolean dedicated(S server);
		@Nullable UUID hostId(S server);
		Iterable<? extends Recipient> recipients(S server);
	}

	/** Pure identity/authority/config/clock ports; no Minecraft or platform initialization in tests. */
	static final class Coordinator<S> {
		private final Consumer<CycleSummary> warnings;
		private final ServerPort<S> port;
		private @Nullable S active;
		private @Nullable Lifetime lifetime;

		Coordinator(ServerPort<S> port) {
			this.warnings = BudgetDelayMonitoring::reportSummary;
			this.port = Objects.requireNonNull(port);
		}

		Coordinator(Consumer<String> warnings, ServerPort<S> port) {
			Objects.requireNonNull(warnings);
			this.warnings = summary -> warnings.accept(formatSummary(summary));
			this.port = Objects.requireNonNull(port);
		}
		BudgetDelaySink sink(@Nullable S server) {
			if (server == null) return BudgetDelaySink.NONE;
			try {
				if (active != server) {
					reset();
					active = server;
					lifetime = new Lifetime(new BudgetDelayMonitor(warnings::accept, modules -> dispatch(server, modules)));
				}
				return lifetime;
			} catch (RuntimeException | LinkageError ignored) { return BudgetDelaySink.NONE; }
		}
		void finishTick(@Nullable S server) {
			if (server == null || active != server || lifetime == null) return;
			boolean enabled = false;
			try { enabled = port.notificationsEnabled(server); }
			catch (RuntimeException | LinkageError ignored) {
				Diagnostics.warn("budget/notice-config", "administrator", () -> "notification setting unavailable; accounting remains active", ignored);
			}
			try { lifetime.monitor.finishTick(port.serverTick(server), enabled); }
			catch (RuntimeException | LinkageError ignored) { /* Diagnostics cannot break the server tick. */ }
		}
		void reset() {
			if (lifetime != null) lifetime.close();
			lifetime = null;
			active = null;
		}
		void stopped(S server) { if (active == server) reset(); }

		private boolean dispatch(S server, Set<Module> modules) {
			@Nullable UUID host = null;
			try { if (!port.dedicated(server)) host = port.hostId(server); }
			catch (RuntimeException | LinkageError ignored) {
				Diagnostics.warn("budget/notice-host", "administrator", () -> "host eligibility unavailable", ignored);
			}
			boolean delivered = false;
			try {
				for (Recipient recipient : port.recipients(server)) {
					try {
						if (recipient.hasPermissions(3) || host != null && host.equals(recipient.id())) {
							recipient.sendSystemMessage(modules);
							delivered = true;
						}
					} catch (RuntimeException | LinkageError ignored) {
						Diagnostics.warn("budget/notice-recipient", "administrator", () -> "modules=" + modules, ignored);
					}
				}
			} catch (RuntimeException | LinkageError ignored) {
				Diagnostics.warn("budget/notice-recipients", "administrator", () -> "modules=" + modules, ignored);
			}
			return delivered;
		}
	}

	private static final class Lifetime implements BudgetDelaySink {
		final BudgetDelayMonitor monitor;
		boolean active = true;
		Lifetime(BudgetDelayMonitor monitor) { this.monitor = monitor; }
		@Override public void observe(@Nullable DelayEvent event) {
			if (!active) return;
			try { monitor.observe(event); } catch (RuntimeException | LinkageError ignored) {}
		}
		@Override public void state(@Nullable Module module, long cycle, long serverTick, @Nullable DelayState state) {
			if (!active) return;
			try { monitor.state(module, cycle, serverTick, state); } catch (RuntimeException | LinkageError ignored) {}
		}
		@Override public void endCycle(@Nullable Module module, long cycle, long serverTick) {
			if (!active) return;
			try { monitor.endCycle(module, cycle, serverTick); } catch (RuntimeException | LinkageError ignored) {}
		}
		void close() { active = false; monitor.reset(); }
	}

	private static final class MinecraftPort implements ServerPort<MinecraftServer> {
		@Override public long serverTick(MinecraftServer server) { return server.getTickCount(); }
		@Override public boolean notificationsEnabled(MinecraftServer server) {
			return ServerConfig.HANDLER.getConfig().isBudgetDelayNotificationsEnabled();
		}
		@Override public boolean dedicated(MinecraftServer server) { return server.isDedicatedServer(); }
		@Override public @Nullable UUID hostId(MinecraftServer server) {
			var profile = server.getSingleplayerProfile();
			return profile == null ? null : profile.getId();
		}
		@Override public Iterable<? extends Recipient> recipients(MinecraftServer server) {
			return () -> server.getPlayerList().getPlayers().stream().<Recipient>map(MinecraftRecipient::new).iterator();
		}
	}
	private record MinecraftRecipient(ServerPlayer player) implements Recipient {
		@Override public UUID id() { return player.getUUID(); }
		@Override public boolean hasPermissions(int level) { return player.hasPermissions(level); }
		@Override public void sendSystemMessage(Set<Module> modules) { player.sendSystemMessage(notice(modules)); }
	}
}
