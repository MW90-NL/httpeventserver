package com.example;

import com.httpeventserver.HttpEventServerPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ExamplePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(HttpEventServerPlugin.class);
		RuneLite.main(args);
	}
}