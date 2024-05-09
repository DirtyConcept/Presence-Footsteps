package eu.ha3.mc.quick.update;

import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.util.JsonHelper;

public record TargettedVersion (
        Version minecraft,
        Version version) {

    public TargettedVersion(JsonObject json) throws VersionParsingException {
        this(
                Version.parse(JsonHelper.getString(json, "minecraft")),
                Version.parse(JsonHelper.getString(json, "version")));
    }

    public TargettedVersion(String modId) {
        this(getVersion("minecraft"), getVersion(modId));
    }

    public static Version getVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(ModContainer::getMetadata)
                .map(ModMetadata::getVersion)
                .orElseThrow(() -> new IllegalArgumentException("Unknown mod id: " + modId));
    }
}