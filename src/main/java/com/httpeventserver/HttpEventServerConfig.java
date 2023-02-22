package com.httpeventserver;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(HttpEventServerConfig.GROUP)
public interface HttpEventServerConfig extends Config
{
    String GROUP = "httpeventserver";

    @ConfigItem(
            position = 1,
            keyName = "apiPort",
            name = "HTTP Port to run the server on",
            description = "HTTP Port to run the server on"
    )
    default String apiPort()
    {
        return "5050";
    }
}
