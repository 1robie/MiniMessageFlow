package fr.robie.minimessageflow.paper;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The build information {@code Bukkit.setServer} asks for (a service a real server provides): for {@link Fakes}.
 */
public final class FakeBuildInfo implements ServerBuildInfo {

    @Override
    public @NotNull Key brandId() {
        return ServerBuildInfo.BRAND_PAPER_ID;
    }

    @Override
    public boolean isBrandCompatible(@NotNull Key brandId) {
        return brandId.equals(this.brandId());
    }

    @Override
    public @NotNull String brandName() {
        return "Fake";
    }

    @Override
    public @NotNull String minecraftVersionId() {
        return "test";
    }

    @Override
    public @NotNull String minecraftVersionName() {
        return "test";
    }

    @Override
    public @NotNull OptionalInt buildNumber() {
        return OptionalInt.empty();
    }

    @Override
    public @NotNull Instant buildTime() {
        return Instant.EPOCH;
    }

    @Override
    public @NotNull Optional<String> gitBranch() {
        return Optional.empty();
    }

    @Override
    public @NotNull Optional<String> gitCommit() {
        return Optional.empty();
    }

    @Override
    public @NotNull String asString(@NotNull StringRepresentation representation) {
        return "fake";
    }
}
