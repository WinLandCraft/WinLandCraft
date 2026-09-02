package dev.winlandcraft;

/** Compatibility shortcut; every app can now be shared from the launcher. */
final class StreamBrowserPanel extends BrowserPanel {
    StreamBrowserPanel(){setAppName("Browser(streamable)");}
    @Override public void open(net.minecraft.client.Minecraft client) {
        super.open(client);
        if(streamClient!=null&&!streaming())streamClient.start(this);
    }
}
