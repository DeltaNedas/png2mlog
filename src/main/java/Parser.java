import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;

import javax.imageio.ImageIO;

public class Parser {
	public static final String defImageFile = "image.png",
		defOutFormat = "output_%d.txt";

	public static String imageFile = defImageFile,
		outFormat = defOutFormat;

	public static int size = 80,
		displayBuffer = 256,
		maxInstructions = 1000;

	private static int lines,
		drawCalls = 0,
		filesWritten = 0;
	// for easy interp.printf
	private static PrintWriter interp = null;
	private static File d = null;

	public static void printHelp() {
		System.out.println(String.join("\n",
			"Usage: pic2mlog [options] [image = " + defImageFile + "]",
			"Valid options:",

			"\t-?/-h/--help",
			"\tShow this information and exit.",

			"\t--",
			"\t\tStop processing options.",
			"\t\tUse if image name may start with '-'",

			"\t-o/--output <format> = " + defOutFormat,
			"\t\tSet the format for output files to be written in.",
			"\t\t'\033[97;1m%d\033[0m' is replaced with the number.",

			"\t-s/--size <pixels> = 80",
			"\t\tSpecify the size of the display.",
			"\t\tImages are automatically resized for you.",

			"\t-b/--buffer <size> = 256",
			"\t\tSet the max number of buffered 'draw' instructions for a display.",
			"\t\tAnuke likes to change this, so keep an eye out.",

			"\t-i/--instructions <max> = 1000",
			"\t\tSet the maximum number of instructions that a processor can have.",
			"\t\tIf your outputs are over 32kb, use -i 666 as it can snugly fit",
			"\t\t into that space.",
			"\t\tUse if Mindustry is updated or you are using modded processors.",

			"\nExit code determines the option from that list which failed to parse,",
			" excluding '-h' and '--'."));
	}

	public static void parseArgs(String[] args) {
		boolean skip = false;
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.charAt(0) == '-' && !skip) {
				String opt = arg.substring(1);
				switch (opt) {
				case "?":
				case "h":
				case "-help":
					printHelp();
					System.exit(0);

				case "-":
					skip = true;
					break;

				case "o":
				case "-output":
					if (++i == args.length) {
						System.err.printf("Option requires an argument: %s\n", arg);
						System.exit(1);
					}

					outFormat = args[i];
					if (!outFormat.contains("%d")) {
						System.err.printf("Output format '%s' does not contain '%%d'!\n", outFormat);
						System.exit(1);
					}
					break;

				case "s":
				case "-size":
					if (++i == args.length) {
						System.err.printf("Option requires an argument: %s\n", arg);
						System.exit(2);
					}

					String sizeStr = args[i];
					try {
						size = Integer.parseInt(sizeStr);
						if (size < 1) {
							throw new RuntimeException("h");
						}
					} catch (Throwable e) {
						System.err.printf("Invalid size: %s\n", sizeStr);
						System.exit(2);
					}
					break;

				case "b":
				case "-buffer":
					if (++i == args.length) {
						System.err.printf("Option requires an argument: %s\n", arg);
						System.exit(3);
					}

					String bufferStr = args[i];
					try {
						displayBuffer = Integer.parseInt(bufferStr);
						if (displayBuffer < 1) {
							throw new RuntimeException("h");
						}
					} catch (Throwable e) {
						System.err.printf("Invalid display buffer: %s\n", bufferStr);
						System.exit(3);
					}
					break;

				case "i":
				case "-instructions":
					if (++i == args.length) {
						System.err.printf("Option requires an argument: %s\n", arg);
						System.exit(4);
					}

					String instStr = args[i];
					try {
						maxInstructions = Integer.parseInt(instStr);
						if (maxInstructions < 1) throw new RuntimeException("h");
					} catch (Throwable e) {
						System.err.printf("Invalid instruction limit: %s\n", instStr);
						System.exit(4);
					}
					break;

				default:
					System.err.printf("Unknown argument '%s'\n", arg);
					System.exit(1);
				}
				continue;
			}

			imageFile = arg;
			break;
		}
	}

	public static void main(String[] args) {
		parseArgs(args);

		try {
			BufferedImage bi = ImageIO.read(new File(imageFile));

			int w = bi.getWidth(),
				h = bi.getHeight();

			if (w != size || h != size) {
				bi = resize(bi);
			}

			// Ensure that interp is created
			lines = maxInstructions;
			checkInstructions();

			for (int x = 0; x < size; x++) {
				for (int y = 0; y < size; y++) {
					int rgb = bi.getRGB(x, y);
					// Have to use int because no unsigned byte
					int red = (rgb >> 16) & 0xff,
						green = (rgb >> 8) & 0xff,
						blue = rgb & 0xff;

					interp.printf("draw color %d %d %d 255\n", red, green, blue);
					interp.printf("draw rect %d %d 1 1\n", x, size - y - 1);
					// side note, the reason there's size-y is because Mindustry starts its origin in the *bottom left* instead of top left, like BufferedImage.
					// that's why we have to do this madness

					checkDraws();
					checkInstructions();
				}
			}
		} catch(IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}

		System.out.printf("Wrote %s files\n", filesWritten);
	}

	// Nicked from https://stackoverflow.com/a/9417836
	private static BufferedImage resize(BufferedImage img) {
		Image tmp = img.getScaledInstance(size, size, Image.SCALE_SMOOTH);
		BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);

		Graphics2D g2d = scaled.createGraphics();
		g2d.drawImage(tmp, 0, 0, null);
		g2d.dispose();

		return scaled;

	}

	private static void checkInstructions() {
		if ((lines += 2) >= maxInstructions) {
			if (interp != null) {
				interp.println("drawflush display1");
				interp.flush();
			}

			String outfile = outFormat.replace("%d", String.valueOf(++filesWritten));
			if (d != null && d.length() > 32767) {
				System.err.printf("\033[1;31mERROR: \033[22;33mOutput file '%s' has over 2^15 - 1 characters.\n" +
					"\033[91mYou will not be able to import this file into Mindustry\033[22;33m, try a lower -i argument.\033[0m",
					outFormat.replace("%d", String.valueOf(filesWritten - 1)));
				System.exit(-1);
			}

			try {
				d = new File(outfile);
				interp = new PrintWriter(d);
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(-1);
			}
			lines = 0;
			drawCalls = 0;
		}
	}

	private static void checkDraws() {
		// FIXME: it's possible that this line breaks off into a new file
		if ((drawCalls += 2) >= displayBuffer) {
			interp.println("drawflush display1");
			lines++;
			drawCalls = 0;
		}
	}
}
