# Easy Farm

A client-side Fabric mod for Minecraft 26.2 with a custom HUD for recording, reviewing and looping keystrokes.

The mod never registers a network channel, sends a packet or reads server data. It is declared `"environment": "client"`, so it
cannot load on a server.

## Opening the HUD

- Run `/ef` in chat. This is a Fabric *client* command, so it is handled locally and never sent to the server.
- Or click the **EF** button next to the inventory (survival and creative).

## Building

Minecraft 26.2 needs **JDK 25**. Make sure `JAVA_HOME` points at it, then:

```bash
./gradlew build              # jar ends up in build/libs/
./gradlew runClient          # launch a dev client
./gradlew runClientGameTest  # drive the real client: /ef, inventory button, tabs; screenshots in build/run/clientGameTest/screenshots
```

Upload `build/libs/easyfarm-<version>.jar` (not the `-sources` jar) to Modrinth, with Fabric API as a required dependency.

## Layout

- `com.easyfarm.command.EasyFarmCommand` registers `/ef`.
- `com.easyfarm.gui.InventoryButton` adds the button to the survival and creative inventories.
- `com.easyfarm.gui.EasyFarmScreen` is the HUD; each tab is a `com.easyfarm.gui.tab.HudTab` (currently placeholders).
- `src/gametest` holds the client game test. It is a separate source set and is never part of the released jar.
