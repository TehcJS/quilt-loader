/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.entrypoint;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * PLEASE NOTE:
 *
 * This class is originally copyrighted under Apache License 2.0
 * by the MCUpdater project (https://github.com/MCUpdater/MCU-Launcher/).
 *
 * It has been adapted here for the purposes of the Fabric loader.
 */
public class AppletFrame extends Frame implements WindowListener {
	private AppletLauncher applet = null;

	public AppletFrame(String title, ImageIcon icon) {
		super(title);
		if (icon != null) {
			Image source = icon.getImage();
			int w = source.getWidth(null);
			int h = source.getHeight(null);
			if (w == -1) { w = 32; h = 32; }
			BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2d = (Graphics2D) image.getGraphics();
			g2d.drawImage(source, 0, 0, null);
			setIconImage(image);
			g2d.dispose();
		}
		this.addWindowListener(this);
	}

	public void launch(String[] args) {
		String username = "Player";
		String sessionid = "0";
		File instance = new File(".");
		if (System.getProperty("minecraft.applet.TargetDirectory") == null) {
			System.setProperty("minecraft.applet.TargetDirectory", instance.toString());
		} else {
			instance = new File(System.getProperty("minecraft.applet.TargetDirectory"));
		}

		applet = new AppletLauncher(instance, username, sessionid, "", "", false);
/*			System.setProperty("org.lwjgl.librarypath", new File(lwjgl, "natives").getAbsolutePath());
		System.setProperty("net.java.games.input.librarypath", new File(lwjgl, "natives").getAbsolutePath()); */
		this.add(applet);
		applet.setPreferredSize(new Dimension(854, 480));
		this.pack();
		this.setLocationRelativeTo(null);
		this.setResizable(true);
		validate();
		applet.init();
		applet.start();
		setVisible(true);
	}

	@Override
	public void windowClosing(WindowEvent e) {
		new Thread(new AppletForcedShutdownListener(30000L)).start();

		if (applet != null) {
			applet.stop();
			applet.destroy();
		}

		System.exit(0);
	}

	@Override
	public void windowOpened(WindowEvent e) {

	}

	@Override
	public void windowActivated(WindowEvent e) {

	}

	@Override
	public void windowClosed(WindowEvent e) {

	}

	@Override
	public void windowIconified(WindowEvent e) {

	}

	@Override
	public void windowDeiconified(WindowEvent e) {

	}

	@Override
	public void windowDeactivated(WindowEvent e) {

	}

}