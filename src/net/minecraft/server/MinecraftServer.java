package net.minecraft.server;

import carpet.helpers.ScoreboardDelta;
import carpet.helpers.ThrowableSuppression;
import carpet.utils.Messenger;
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import net.minecraft.advancements.AdvancementManager;
import net.minecraft.advancements.FunctionManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.ServerCommandManager;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.ICrashReportDetail;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.NetworkSystem;
import net.minecraft.network.ServerStatusResponse;
import net.minecraft.network.play.server.SPacketTimeUpdate;
import net.minecraft.profiler.ISnooperInfo;
import net.minecraft.profiler.Profiler;
import net.minecraft.profiler.Snooper;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.ITickable;
import net.minecraft.util.ReportedException;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.util.datafix.DataFixesManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.ServerWorldEventHandler;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldServerDemo;
import net.minecraft.world.WorldServerMulti;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.storage.AnvilSaveConverter;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;

import redstone.multimeter.common.TickTask;
import redstone.multimeter.helper.WorldHelper;

import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import carpet.CarpetServer;
import carpet.CarpetSettings;
import carpet.carpetclient.CarpetClientChunkLogger;
import carpet.helpers.LagSpikeHelper;
import carpet.helpers.TickSpeed;
import carpet.utils.CarpetProfiler;
import carpet.utils.PistonFixes;
import carpet.utils.TickingArea;

public abstract class MinecraftServer implements ICommandSender, Runnable, IThreadListener, ISnooperInfo
{
    private static final Logger LOGGER = LogManager.getLogger();
    public static final File USER_CACHE_FILE = new File("usercache.json");
    private final ISaveFormat anvilConverterForAnvilFile;
    /** The PlayerUsageSnooper instance. */
    private final Snooper usageSnooper = new Snooper("server", this, getCurrentTimeMillis());
    private final File anvilFile;
    /** List of names of players who are online. */
    private final List<ITickable> tickables = Lists.<ITickable>newArrayList();
    public final ICommandManager commandManager;
    public final Profiler profiler = new Profiler();
    private final NetworkSystem networkSystem;
    private final ServerStatusResponse statusResponse = new ServerStatusResponse();
    private final Random random = new Random();
    private final DataFixer dataFixer;
    /** The server's hostname. */
    private String hostname;
    /** The server's port. */
    private int serverPort = -1;
    /** The server world instances. */
    public WorldServer[] worlds;
    /** The player list for this server */
    private PlayerList playerList;
    /** Indicates whether the server is running or not. Set to false to initiate a shutdown. */
    private boolean serverRunning = true;
    /** Indicates to other classes that the server is safely stopped. */
    private boolean serverStopped;
    /** Incremented every tick. */
    private int tickCounter;
    protected final Proxy serverProxy;
    /** The task the server is currently working on(and will output on outputPercentRemaining). */
    public String currentTask;
    /** The percentage of the current task finished so far. */
    public int percentDone;
    /** True if the server is in online mode. */
    private boolean onlineMode;
    private boolean preventProxyConnections;
    /** True if the server has animals turned on. */
    private boolean canSpawnAnimals;
    private boolean canSpawnNPCs;
    /** Indicates whether PvP is active on the server or not. */
    private boolean pvpEnabled;
    /** Determines if flight is allowed or not. */
    private boolean allowFlight;
    /** The server MOTD string. */
    private String motd;
    /** Maximum build height. */
    private int buildLimit;
    private int maxPlayerIdleMinutes;
    public final long[] tickTimeArray = new long[100];
    /** Stats are [dimension][tick%100] system.nanoTime is stored. */
    public long[][] timeOfLastDimensionTick;
    private KeyPair serverKeyPair;
    /** Username of the server owner (for integrated servers) */
    private String serverOwner;
    private String folderName;
    private boolean isDemo;
    private boolean enableBonusChest;
    /** The texture pack for the server */
    private String resourcePackUrl = "";
    private String resourcePackHash = "";
    private boolean serverIsRunning;
    /** Set when warned for "Can't keep up", which triggers again after 15 seconds. */
    private long timeOfLastWarning;
    private String userMessage;
    private boolean startProfiling;
    private boolean isGamemodeForced;
    private final YggdrasilAuthenticationService authService;
    private final MinecraftSessionService sessionService;
    private final GameProfileRepository profileRepo;
    private final PlayerProfileCache profileCache;
    private long nanoTimeSinceStatusRefresh;
    protected final Queue < FutureTask<? >> futureTaskQueue = Queues. < FutureTask<? >> newArrayDeque();
    private Thread serverThread;
    private long currentTime = getCurrentTimeMillis();

    public MinecraftServer(File anvilFileIn, Proxy proxyIn, DataFixer dataFixerIn, YggdrasilAuthenticationService authServiceIn, MinecraftSessionService sessionServiceIn, GameProfileRepository profileRepoIn, PlayerProfileCache profileCacheIn)
    {
        CarpetServer.init(this); //CM init
        this.serverProxy = proxyIn;
        this.authService = authServiceIn;
        this.sessionService = sessionServiceIn;
        this.profileRepo = profileRepoIn;
        this.profileCache = profileCacheIn;
        this.anvilFile = anvilFileIn;
        this.networkSystem = new NetworkSystem(this);
        this.commandManager = this.createCommandManager();
        this.anvilConverterForAnvilFile = new AnvilSaveConverter(anvilFileIn, dataFixerIn);
        this.dataFixer = dataFixerIn;
    }

    protected ServerCommandManager createCommandManager()
    {
        return new ServerCommandManager(this);
    }

    /**
     * Initialises the server and starts it.
     */
    public abstract boolean init() throws IOException;

    protected void convertMapIfNeeded(String worldNameIn)
    {
        if (this.getActiveAnvilConverter().isOldMapFormat(worldNameIn))
        {
            LOGGER.info("Converting map!");
            this.setUserMessage("menu.convertingLevel");
            this.getActiveAnvilConverter().convertMapFormat(worldNameIn, new IProgressUpdate()
            {
                private long startTime = System.currentTimeMillis();
                /**
                 * Shows the 'Saving level' string.
                 */
                public void displaySavingString(String message)
                {
                }
                /**
                 * Updates the progress bar on the loading screen to the specified amount.
                 */
                public void setLoadingProgress(int progress)
                {
                    if (System.currentTimeMillis() - this.startTime >= 1000L)
                    {
                        this.startTime = System.currentTimeMillis();
                        MinecraftServer.LOGGER.info("Converting... {}%", (int)progress);
                    }
                }
                /**
                 * Displays a string on the loading screen supposed to indicate what is being done currently.
                 */
                public void displayLoadingString(String message)
                {
                }
            });
        }
    }

    /**
     * Typically "menu.convertingLevel", "menu.loadingLevel" or others.
     */
    protected synchronized void setUserMessage(String message)
    {
        this.userMessage = message;
    }

    public void loadAllWorlds(String saveName, String worldNameIn, long seed, WorldType type, String generatorOptions)
    {
        this.convertMapIfNeeded(saveName);
        this.setUserMessage("menu.loadingLevel");
        this.worlds = new WorldServer[3];
        this.timeOfLastDimensionTick = new long[this.worlds.length][100];
        ISaveHandler isavehandler = this.anvilConverterForAnvilFile.getSaveLoader(saveName, true);
        this.setResourcePackFromWorld(this.getFolderName(), isavehandler);
        WorldInfo worldinfo = isavehandler.loadWorldInfo();
        WorldSettings worldsettings;

        if (worldinfo == null)
        {
            if (this.isDemo())
            {
                worldsettings = WorldServerDemo.DEMO_WORLD_SETTINGS;
            }
            else
            {
                worldsettings = new WorldSettings(seed, this.getGameType(), this.canStructuresSpawn(), this.isHardcore(), type);
                worldsettings.setGeneratorOptions(generatorOptions);

                if (this.enableBonusChest)
                {
                    worldsettings.enableBonusChest();
                }
            }

            worldinfo = new WorldInfo(worldsettings, worldNameIn);
        }
        else
        {
            worldinfo.setWorldName(worldNameIn);
            worldsettings = new WorldSettings(worldinfo);
        }

        for (int i = 0; i < this.worlds.length; ++i)
        {
            int j = 0;

            if (i == 1)
            {
                j = -1;
            }

            if (i == 2)
            {
                j = 1;
            }

            if (i == 0)
            {
                if (this.isDemo())
                {
                    this.worlds[i] = (WorldServer)(new WorldServerDemo(this, isavehandler, worldinfo, j, this.profiler)).init();
                }
                else
                {
                    this.worlds[i] = (WorldServer)(new WorldServer(this, isavehandler, worldinfo, j, this.profiler)).init();
                }

                this.worlds[i].initialize(worldsettings);
            }
            else
            {
                this.worlds[i] = (WorldServer)(new WorldServerMulti(this, isavehandler, j, this.worlds[0], this.profiler)).init();
            }

            this.worlds[i].addEventListener(new ServerWorldEventHandler(this, this.worlds[i]));

            if (!this.isSinglePlayer())
            {
                this.worlds[i].getWorldInfo().setGameType(this.getGameType());
            }
        }

        this.playerList.setPlayerManager(this.worlds);
        this.setDifficultyForAllWorlds(this.getDifficulty());
        CarpetServer.onLoadAllWorlds(this);
        this.initialWorldChunkLoad();
        CarpetServer.loadBots(this);
    }

    protected void initialWorldChunkLoad()
    {
        int i = 16;
        int j = 4;
        int k = 192;
        int l = 625;
        int i1 = 0;
        this.setUserMessage("menu.generatingTerrain");
        int j1 = 0;
        if (CarpetSettings.tickingAreas)
        {
            TickingArea.initialChunkLoad(this, true);
        }
        if (!CarpetSettings.disableSpawnChunks)
        {
            LOGGER.info("Preparing start region for level 0");
            WorldServer worldserver = this.worlds[0];
            BlockPos blockpos = worldserver.getSpawnPoint();
            long k1 = getCurrentTimeMillis();

            for (int l1 = -192; l1 <= 192 && this.isServerRunning(); l1 += 16)
            {
                for (int i2 = -192; i2 <= 192 && this.isServerRunning(); i2 += 16)
                {
                    long j2 = getCurrentTimeMillis();

                    if (j2 - k1 > 1000L)
                    {
                        this.outputPercentRemaining("Preparing spawn area", i1 * 100 / 625);
                        k1 = j2;
                    }

                    ++i1;
                    worldserver.getChunkProvider().provideChunk(blockpos.getX() + l1 >> 4, blockpos.getZ() + i2 >> 4);
                }
            }
        }

        this.clearCurrentTask();
    }

    protected void setResourcePackFromWorld(String worldNameIn, ISaveHandler saveHandlerIn)
    {
        File file1 = new File(saveHandlerIn.getWorldDirectory(), "resources.zip");

        if (file1.isFile())
        {
            try
            {
                this.setResourcePack("level://" + URLEncoder.encode(worldNameIn, StandardCharsets.UTF_8.toString()) + "/" + "resources.zip", "");
            }
            catch (UnsupportedEncodingException var5)
            {
                LOGGER.warn("Something went wrong url encoding {}", (Object)worldNameIn);
            }
        }
    }

    public abstract boolean canStructuresSpawn();

    public abstract GameType getGameType();

    /**
     * Get the server's difficulty
     */
    public abstract EnumDifficulty getDifficulty();

    /**
     * Defaults to false.
     */
    public abstract boolean isHardcore();

    public abstract int getOpPermissionLevel();

    /**
     * Get if RCON command events should be broadcast to ops
     */
    public abstract boolean shouldBroadcastRconToOps();

    /**
     * Get if console command events should be broadcast to ops
     */
    public abstract boolean shouldBroadcastConsoleToOps();

    /**
     * Used to display a percent remaining given text and the percentage.
     */
    public void outputPercentRemaining(String message, int percent) // CM changed visibility to public
    {
        this.currentTask = message;
        this.percentDone = percent;
        LOGGER.info("{}: {}%", message, Integer.valueOf(percent));
    }

    /**
     * Set current task to null and set its percentage to 0.
     */
    protected void clearCurrentTask()
    {
        this.currentTask = null;
        this.percentDone = 0;
    }

    /**
     * par1 indicates if a log message should be output.
     */
    protected void saveAllWorlds(boolean isSilent)
    {
        for (WorldServer worldserver : this.worlds)
        {
            if (worldserver != null)
            {
                if (!isSilent)
                {
                    LOGGER.info("Saving chunks for level '{}'/{}", worldserver.getWorldInfo().getWorldName(), worldserver.provider.getDimensionType().getName());
                }

                try
                {
                    worldserver.saveAllChunks(true, (IProgressUpdate)null);
                }
                catch (MinecraftException minecraftexception)
                {
                    LOGGER.warn(minecraftexception.getMessage());
                }
            }
        }
        CarpetServer.onWorldsSaved(this);
    }

    /**
     * Saves all necessary data as preparation for stopping the server.
     */
    protected void stopServer()
    {
        LOGGER.info("Stopping server");

        if (this.getNetworkSystem() != null)
        {
            this.getNetworkSystem().terminateEndpoints();
        }

        if (this.playerList != null)
        {
            LOGGER.info("Saving players");
            this.playerList.removeBotTeam();
            this.playerList.storeFakePlayerData();
            this.playerList.saveAllPlayerData();
            this.playerList.removeAllPlayers();
        }

        if (this.worlds != null)
        {
            LOGGER.info("Saving worlds");

            for (WorldServer worldserver : this.worlds)
            {
                if (worldserver != null)
                {
                    worldserver.disableLevelSaving = false;
                }
            }

            this.saveAllWorlds(false);

            for (WorldServer worldserver1 : this.worlds)
            {
                if (worldserver1 != null)
                {
                    worldserver1.flush();
                }
            }
        }

        if (this.usageSnooper.isSnooperRunning())
        {
            this.usageSnooper.stopSnooper();
        }
    }

    /**
     * "getHostname" is already taken, but both return the hostname.
     */
    public String getServerHostname()
    {
        return this.hostname;
    }

    public void setHostname(String host)
    {
        this.hostname = host;
    }

    public boolean isServerRunning()
    {
        return this.serverRunning;
    }

    /**
     * Sets the serverRunning variable to false, in order to get the server to shut down.
     */
    public void initiateShutdown()
    {
        this.serverRunning = false;
    }

    public void run()
    {
        try
        {
            if (this.init())
            {
                this.currentTime = getCurrentTimeMillis();
                long i = 0L;
                if ("_".equals(CarpetSettings.customMOTD))
                    this.statusResponse.setServerDescription(new TextComponentString(this.motd));
                else
                    this.statusResponse.setServerDescription(new TextComponentString(CarpetSettings.customMOTD));
                this.statusResponse.setVersion(new ServerStatusResponse.Version("1.12.2", 340));
                this.applyServerIconToResponse(this.statusResponse);

                while (this.serverRunning)
                {
                    /* carpet mod commandTick */
                    //todo check if this check is necessary
                    if (TickSpeed.time_warp_start_time != 0)
                    {
                        if (TickSpeed.continueWarp())
                        {
                            this.tick();
                            this.currentTime = getCurrentTimeMillis();
                            this.serverIsRunning = true;
                        }
                        continue;
                    }
                    /* end */
                    long k = getCurrentTimeMillis();
                    long j = k - this.currentTime;

                    if (j > 2000L && this.currentTime - this.timeOfLastWarning >= 15000L)
                    {
                        j = 2000L;
                        this.timeOfLastWarning = this.currentTime;
                    }

                    if (j < 0L)
                    {
                        LOGGER.warn("Time ran backwards! Did the system time change?");
                        j = 0L;
                    }

                    i += j;
                    this.currentTime = k;
                    boolean falling_behind = false;

                    if (this.worlds[0].areAllPlayersAsleep())
                    {
                        this.tick();
                        i = 0L;
                    }
                    else
                    {
                        boolean keeping_up = false;
                        while (i > TickSpeed.mspt) /* carpet mod 50L */
                        {
                            i -= TickSpeed.mspt; /* carpet mod 50L */
                            if (CarpetSettings.watchdogFix && keeping_up)
                            {
                                this.currentTime = getCurrentTimeMillis();
                                this.serverIsRunning = true;
                                falling_behind = true;
                            }
                            this.tick();
                            keeping_up = true;
                            if (CarpetSettings.disableVanillaTickWarp) {
                                i = getCurrentTimeMillis() - k;
                                break;
                            }
                        }
                    }

                    if (falling_behind)
                    {
                        Thread.sleep(1L); /* carpet mod 50L */
                    }
                    else
                    {
                        Thread.sleep(Math.max(1L, TickSpeed.mspt - i)); /* carpet mod 50L */
                    }
                    this.serverIsRunning = true;
                }
            }
            else
            {
                this.finalTick((CrashReport)null);
            }
        }
        catch (Throwable throwable1)
        {
            LOGGER.error("Encountered an unexpected exception", throwable1);
            CrashReport crashreport = null;

            if (throwable1 instanceof ReportedException)
            {
                crashreport = this.addServerInfoToCrashReport(((ReportedException)throwable1).getCrashReport());
            }
            else
            {
                crashreport = this.addServerInfoToCrashReport(new CrashReport("Exception in server tick loop", throwable1));
            }

            File file1 = new File(new File(this.getDataDirectory(), "crash-reports"), "crash-" + (new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss")).format(new Date()) + "-server.txt");

            if (crashreport.saveToFile(file1))
            {
                LOGGER.error("This crash report has been saved to: {}", (Object)file1.getAbsolutePath());
            }
            else
            {
                LOGGER.error("We were unable to save this crash report to disk.");
            }

            this.finalTick(crashreport);
        }
        finally
        {
            try
            {
                this.serverStopped = true;
                this.stopServer();
            }
            catch (Throwable throwable)
            {
                LOGGER.error("Exception stopping the server", throwable);
            }
            finally
            {
                this.systemExitNow();
            }
        }
    }

    public void applyServerIconToResponse(ServerStatusResponse response)
    {
        File file1 = this.getFile("server-icon.png");

        if (!file1.exists())
        {
            file1 = this.getActiveAnvilConverter().getFile(this.getFolderName(), "icon.png");
        }

        if (file1.isFile())
        {
            ByteBuf bytebuf = Unpooled.buffer();

            try
            {
                BufferedImage bufferedimage = ImageIO.read(file1);
                Validate.validState(bufferedimage.getWidth() == 64, "Must be 64 pixels wide");
                Validate.validState(bufferedimage.getHeight() == 64, "Must be 64 pixels high");
                ImageIO.write(bufferedimage, "PNG", new ByteBufOutputStream(bytebuf));
                ByteBuf bytebuf1 = Base64.encode(bytebuf);
                response.setFavicon("data:image/png;base64," + bytebuf1.toString(StandardCharsets.UTF_8));
            }
            catch (Exception exception)
            {
                LOGGER.error("Couldn't load server icon", (Throwable)exception);
            }
            finally
            {
                bytebuf.release();
            }
        }
    }

    public File getDataDirectory()
    {
        return new File(".");
    }

    /**
     * Called on exit from the main run() loop.
     */
    protected void finalTick(CrashReport report)
    {
    }

    /**
     * Directly calls System.exit(0), instantly killing the program.
     */
    public void systemExitNow()
    {
    }

    /**
     * Main function called by run() every loop.
     */
    protected void tick()
    {
        long i = System.nanoTime();
        ++this.tickCounter;

        CarpetServer.tick(this);
        if (CarpetProfiler.tick_health_requested != 0L)
        {
            CarpetProfiler.start_tick_profiling();
        }

        CarpetServer.rsmmServer.tickStart(); // RSMM
        WorldHelper.startTickTask(TickTask.TICK); // RSMM

        if (this.startProfiling)
        {
            this.startProfiling = false;
            this.profiler.profilingEnabled = true;
            this.profiler.clearProfiling();
        }

        this.profiler.startSection("root");
        this.updateTimeLightAndEntities();

        if (i - this.nanoTimeSinceStatusRefresh >= 5000000000L)
        {
            this.nanoTimeSinceStatusRefresh = i;
            this.statusResponse.setPlayers(new ServerStatusResponse.Players(this.getMaxPlayers(), this.getCurrentPlayerCount()));
            GameProfile[] agameprofile = new GameProfile[Math.min(this.getCurrentPlayerCount(), 12)];
            int j = MathHelper.getInt(this.random, 0, this.getCurrentPlayerCount() - agameprofile.length);

            for (int k = 0; k < agameprofile.length; ++k)
            {
                agameprofile[k] = ((EntityPlayerMP)this.playerList.getPlayers().get(j + k)).getGameProfile();
            }

            Collections.shuffle(Arrays.asList(agameprofile));
            this.statusResponse.getPlayers().setPlayers(agameprofile);
        }

        LagSpikeHelper.processLagSpikes(null, LagSpikeHelper.TickPhase.AUTOSAVE, LagSpikeHelper.PrePostSubPhase.PRE);
        if (this.tickCounter % 900 == 0)
        {
            CarpetProfiler.start_section(null, "Autosave");
            this.profiler.startSection("save");
            WorldHelper.startTickTask(TickTask.AUTOSAVE); // RSMM
            this.playerList.storeFakePlayerData();
            this.playerList.saveAllPlayerData();
            if(carpet.carpetclient.CarpetClientChunkLogger.logger.enabled)
                carpet.carpetclient.CarpetClientChunkLogger.setReason("Autosave queuing chunks for unloading");
            this.saveAllWorlds(true);
            carpet.carpetclient.CarpetClientChunkLogger.resetReason();
            this.profiler.endSection();
            WorldHelper.endTickTask(); // RSMM
            CarpetProfiler.end_current_section();
        }
        LagSpikeHelper.processLagSpikes(null, LagSpikeHelper.TickPhase.AUTOSAVE, LagSpikeHelper.PrePostSubPhase.POST);

        this.profiler.startSection("tallying");
        this.tickTimeArray[this.tickCounter % 100] = System.nanoTime() - i;
        this.profiler.endSection();
        this.profiler.startSection("snooper");

        if (!this.usageSnooper.isSnooperRunning() && this.tickCounter > 100)
        {
            this.usageSnooper.startSnooper();
        }

        if (this.tickCounter % 6000 == 0)
        {
            this.usageSnooper.addMemoryStatsToSnooper();
        }

        this.profiler.endSection();
        this.profiler.endSection();

        WorldHelper.endTickTask(); // RSMM
        CarpetServer.rsmmServer.tickEnd(); // RSMM

        // ChunkLogger - 0x-CARPET
        if(CarpetClientChunkLogger.logger.enabled) {
            CarpetClientChunkLogger.logger.sendAll();
        }

        LagSpikeHelper.processLagSpikes(null, LagSpikeHelper.TickPhase.TICK, LagSpikeHelper.PrePostSubPhase.POST);

        if (CarpetProfiler.tick_health_requested != 0L)
        {
            CarpetProfiler.end_tick_profiling(this);
        }

        if(CarpetSettings.scoreboardDelta > 0 && tickCounter % 20 == 0){
            ScoreboardDelta.update();
        }
    }

    public void updateTimeLightAndEntities()
    {
        LagSpikeHelper.processLagSpikes(null, LagSpikeHelper.TickPhase.TICK, LagSpikeHelper.PrePostSubPhase.PRE);
        this.profiler.startSection("jobs");
        WorldHelper.startTickTask(TickTask.PACKETS); // RSMM

        LagSpikeHelper.processLagSpikes(null, LagSpikeHelper.TickPhase.PLAYER, LagSpikeHelper.PrePostSubPhase.PRE);
        synchronized (this.futureTaskQueue)
        {
            while (!this.futureTaskQueue.isEmpty())
            {
                Util.runTask(this.futureTaskQueue.poll(), LOGGER);
            }
        }
        LagSpikeHelper.processLagSpikes(null, LagSpikeHelper.TickPhase.PLAYER, LagSpikeHelper.PrePostSubPhase.POST);

        this.profiler.endStartSection("levels");
        WorldHelper.swapTickTask(TickTask.LEVELS); // RSMM

        for (int j = 0; j < this.worlds.length; ++j)
        {
            long i = System.nanoTime();

            if (j == 0 || this.getAllowNether())
            {
                WorldServer worldserver = this.worlds[j];
                this.profiler.func_194340_a(() ->
                {
                    return worldserver.getWorldInfo().getWorldName();
                });
                LagSpikeHelper.processLagSpikes(null, LagSpikeHelper.TickPhase.DIMENSION, LagSpikeHelper.PrePostSubPhase.PRE);

                if (this.tickCounter % 20 == 0)
                {
                    this.profiler.startSection("timeSync");
                    this.playerList.sendPacketToAllPlayersInDimension(new SPacketTimeUpdate(worldserver.getTotalWorldTime(), worldserver.getWorldTime(), worldserver.getGameRules().getBoolean("doDaylightCycle")), worldserver.provider.getDimensionType().getId());
                    this.profiler.endSection();
                }

                this.profiler.startSection("tick");

                try
                {
                    worldserver.tick();
                }
                catch (ThrowableSuppression e)
                {
                    Messenger.print_server_message(this, "You just caused a server crash in world tick.");
                }
                catch (Throwable throwable1)
                {
                    CrashReport crashreport = CrashReport.makeCrashReport(throwable1, "Exception ticking world");
                    worldserver.addWorldInfoToCrashReport(crashreport);
                    if(!CarpetSettings.updateSuppressionCrashFix || !(crashreport.getCrashCause() instanceof ThrowableSuppression) ) {
                        throw new ReportedException(crashreport);
                    } else {
                        Messenger.print_server_message(this, "You just caused a server crash in world tick.");
                    }
                }

                try
                {
                    WorldHelper.startTickTask(TickTask.ENTITIES); // RSMM
                    worldserver.updateEntities();
                    WorldHelper.endTickTask(); // RSMM
                }
                catch (ThrowableSuppression e)
                {
                    Messenger.print_server_message(this, "You just caused a server crash in update entities.");
                }
                catch (Throwable throwable)
                {
                    CrashReport crashreport1 = CrashReport.makeCrashReport(throwable, "Exception ticking world entities");
                    worldserver.addWorldInfoToCrashReport(crashreport1);
                    if(!CarpetSettings.updateSuppressionCrashFix || !(crashreport1.getCrashCause() instanceof ThrowableSuppression) ) {
                        throw new ReportedException(crashreport1);
                    } else {
                        Messenger.print_server_message(this, "You just caused a server crash in update entities.");
                    }
                }

                this.profiler.endSection();
                this.profiler.startSection("tracker");
                worldserver.getEntityTracker().tick();
                LagSpikeHelper.processLagSpikes(null, LagSpikeHelper.TickPhase.DIMENSION, LagSpikeHelper.PrePostSubPhase.POST);
                this.profiler.endSection();
                this.profiler.endSection();
            }

            this.timeOfLastDimensionTick[j][this.tickCounter % 100] = System.nanoTime() - i;
        }

        CarpetProfiler.start_section(null, "Network");
        this.profiler.endStartSection("connection");
        WorldHelper.swapTickTask(TickTask.CONNECTIONS); // RSMM
        this.getNetworkSystem().networkTick();
        this.profiler.endStartSection("players");
        WorldHelper.swapTickTask(TickTask.PLAYER_PING); // RSMM
        this.playerList.onTick();
        CarpetProfiler.end_current_section();
        this.profiler.endStartSection("commandFunctions");
        WorldHelper.swapTickTask(TickTask.COMMAND_FUNCTIONS); // RSMM
        this.getFunctionManager().update();
        this.profiler.endStartSection("tickables");
        WorldHelper.swapTickTask(TickTask.SERVER_GUI); // RSMM

        for (int k = 0; k < this.tickables.size(); ++k)
        {
            ((ITickable)this.tickables.get(k)).update();
        }

        this.profiler.endSection();
        WorldHelper.endTickTask(); // RSMM

        PistonFixes.onEndTick();
    }

    public boolean getAllowNether()
    {
        return true;
    }

    public void registerTickable(ITickable tickable)
    {
        this.tickables.add(tickable);
    }

    public static void main(String[] p_main_0_)
    {
        Bootstrap.register();

        try
        {
            boolean flag = true;
            String s = null;
            String s1 = ".";
            String s2 = null;
            boolean flag1 = false;
            boolean flag2 = false;
            int l = -1;

            for (int i1 = 0; i1 < p_main_0_.length; ++i1)
            {
                String s3 = p_main_0_[i1];
                String s4 = i1 == p_main_0_.length - 1 ? null : p_main_0_[i1 + 1];
                boolean flag3 = false;

                if (!"nogui".equals(s3) && !"--nogui".equals(s3))
                {
                    if ("--port".equals(s3) && s4 != null)
                    {
                        flag3 = true;

                        try
                        {
                            l = Integer.parseInt(s4);
                        }
                        catch (NumberFormatException var13)
                        {
                            ;
                        }
                    }
                    else if ("--singleplayer".equals(s3) && s4 != null)
                    {
                        flag3 = true;
                        s = s4;
                    }
                    else if ("--universe".equals(s3) && s4 != null)
                    {
                        flag3 = true;
                        s1 = s4;
                    }
                    else if ("--world".equals(s3) && s4 != null)
                    {
                        flag3 = true;
                        s2 = s4;
                    }
                    else if ("--demo".equals(s3))
                    {
                        flag1 = true;
                    }
                    else if ("--bonusChest".equals(s3))
                    {
                        flag2 = true;
                    }
                }
                else
                {
                    flag = false;
                }

                if (flag3)
                {
                    ++i1;
                }
            }

            YggdrasilAuthenticationService yggdrasilauthenticationservice = new YggdrasilAuthenticationService(Proxy.NO_PROXY, UUID.randomUUID().toString());
            MinecraftSessionService minecraftsessionservice = yggdrasilauthenticationservice.createMinecraftSessionService();
            GameProfileRepository gameprofilerepository = yggdrasilauthenticationservice.createProfileRepository();
            PlayerProfileCache playerprofilecache = new PlayerProfileCache(gameprofilerepository, new File(s1, USER_CACHE_FILE.getName()));
            final DedicatedServer dedicatedserver = new DedicatedServer(new File(s1), DataFixesManager.createFixer(), yggdrasilauthenticationservice, minecraftsessionservice, gameprofilerepository, playerprofilecache);

            if (s != null)
            {
                dedicatedserver.setServerOwner(s);
            }

            if (s2 != null)
            {
                dedicatedserver.setFolderName(s2);
            }

            if (l >= 0)
            {
                dedicatedserver.setServerPort(l);
            }

            if (flag1)
            {
                dedicatedserver.setDemo(true);
            }

            if (flag2)
            {
                dedicatedserver.canCreateBonusChest(true);
            }

            if (flag && !GraphicsEnvironment.isHeadless())
            {
                dedicatedserver.setGuiEnabled();
            }

            dedicatedserver.startServerThread();
            Runtime.getRuntime().addShutdownHook(new Thread("Server Shutdown Thread")
            {
                public void run()
                {
                    dedicatedserver.stopServer();
                }
            });
        }
        catch (Exception exception)
        {
            LOGGER.fatal("Failed to start the minecraft server", (Throwable)exception);
        }
    }

    public void startServerThread()
    {
        this.serverThread = new Thread(this, "Server thread");
        this.serverThread.start();
    }

    /**
     * Returns a File object from the specified string.
     */
    public File getFile(String fileName)
    {
        return new File(this.getDataDirectory(), fileName);
    }

    /**
     * Logs the message with a level of INFO.
     */
    public void logInfo(String msg)
    {
        LOGGER.info(msg);
    }

    /**
     * Logs the message with a level of WARN.
     */
    public void logWarning(String msg)
    {
        LOGGER.warn(msg);
    }

    /**
     * Gets the worldServer by the given dimension.
     */
    public WorldServer getWorld(int dimension)
    {
        if (dimension == -1)
        {
            return this.worlds[1];
        }
        else
        {
            return dimension == 1 ? this.worlds[2] : this.worlds[0];
        }
    }

    /**
     * Returns the server's Minecraft version as string.
     */
    public String getMinecraftVersion()
    {
        return "1.12.2";
    }

    /**
     * Returns the number of players currently on the server.
     */
    public int getCurrentPlayerCount()
    {
        return this.playerList.getCurrentPlayerCount();
    }

    /**
     * Returns the maximum number of players allowed on the server.
     */
    public int getMaxPlayers()
    {
        return this.playerList.getMaxPlayers();
    }

    /**
     * Returns an array of the usernames of all the connected players.
     */
    public String[] getOnlinePlayerNames()
    {
        return this.playerList.getOnlinePlayerNames();
    }

    /**
     * Returns an array of the GameProfiles of all the connected players
     */
    public GameProfile[] getOnlinePlayerProfiles()
    {
        return this.playerList.getOnlinePlayerProfiles();
    }

    /**
     * Returns true if debugging is enabled, false otherwise.
     */
    public boolean isDebuggingEnabled()
    {
        return false;
    }

    /**
     * Logs the error message with a level of SEVERE.
     */
    public void logSevere(String msg)
    {
        LOGGER.error(msg);
    }

    /**
     * If isDebuggingEnabled(), logs the message with a level of INFO.
     */
    public void logDebug(String msg)
    {
        if (this.isDebuggingEnabled())
        {
            LOGGER.info(msg);
        }
    }

    public String getServerModName()
    {
        return "carpetmod";
    }

    /**
     * Adds the server info, including from theWorldServer, to the crash report.
     */
    public CrashReport addServerInfoToCrashReport(CrashReport report)
    {
        report.getCategory().addDetail("Profiler Position", new ICrashReportDetail<String>()
        {
            public String call() throws Exception
            {
                return MinecraftServer.this.profiler.profilingEnabled ? MinecraftServer.this.profiler.getNameOfLastSection() : "N/A (disabled)";
            }
        });

        if (this.playerList != null)
        {
            report.getCategory().addDetail("Player Count", new ICrashReportDetail<String>()
            {
                public String call()
                {
                    return MinecraftServer.this.playerList.getCurrentPlayerCount() + " / " + MinecraftServer.this.playerList.getMaxPlayers() + "; " + MinecraftServer.this.playerList.getPlayers();
                }
            });
        }

        return report;
    }

    public List<String> getTabCompletions(ICommandSender sender, String input, @Nullable BlockPos pos, boolean hasTargetBlock)
    {
        List<String> list = Lists.<String>newArrayList();
        boolean flag = input.startsWith("/");

        if (flag)
        {
            input = input.substring(1);
        }

        if (!flag && !hasTargetBlock)
        {
            String[] astring = input.split(" ", -1);
            String s2 = astring[astring.length - 1];

            for (String s1 : this.playerList.getOnlinePlayerNames())
            {
                if (CommandBase.doesStringStartWith(s2, s1))
                {
                    list.add(s1);
                }
            }

            return list;
        }
        else
        {
            boolean flag1 = !input.contains(" ");
            List<String> list1 = this.commandManager.getTabCompletions(sender, input, pos);

            if (!list1.isEmpty())
            {
                for (String s : list1)
                {
                    if (flag1 && !hasTargetBlock)
                    {
                        list.add("/" + s);
                    }
                    else
                    {
                        list.add(s);
                    }
                }
            }

            return list;
        }
    }

    public boolean isAnvilFileSet()
    {
        return this.anvilFile != null;
    }

    /**
     * Gets the name of this thing. This method has slightly different behavior depending on the interface (for <a
     * href="https://github.com/ModCoderPack/MCPBot-Issues/issues/14">technical reasons</a> the same method is used for
     * both IWorldNameable and ICommandSender):
     *  
     * <dl>
     * <dt>{@link net.minecraft.util.INameable#getName() INameable.getName()}</dt>
     * <dd>Returns the name of this inventory. If this {@linkplain net.minecraft.inventory#hasCustomName() has a custom
     * name} then this <em>should</em> be a direct string; otherwise it <em>should</em> be a valid translation
     * string.</dd>
     * <dd>However, note that <strong>the translation string may be invalid</strong>, as is the case for {@link
     * net.minecraft.tileentity.TileEntityBanner TileEntityBanner} (always returns nonexistent translation code
     * <code>banner</code> without a custom name), {@link net.minecraft.block.BlockAnvil.Anvil BlockAnvil$Anvil} (always
     * returns <code>anvil</code>), {@link net.minecraft.block.BlockWorkbench.InterfaceCraftingTable
     * BlockWorkbench$InterfaceCraftingTable} (always returns <code>crafting_table</code>), {@link
     * net.minecraft.inventory.InventoryCraftResult InventoryCraftResult} (always returns <code>Result</code>) and the
     * {@link net.minecraft.entity.item.EntityMinecart EntityMinecart} family (uses the entity definition). This is not
     * an exaustive list.</dd>
     * <dd>In general, this method should be safe to use on tile entities that implement IInventory.</dd>
     * <dt>{@link net.minecraft.command.ICommandSender#getName() ICommandSender.getName()} and {@link
     * net.minecraft.entity.Entity#getName() Entity.getName()}</dt>
     * <dd>Returns a valid, displayable name (which may be localized). For most entities, this is the translated version
     * of its translation string (obtained via {@link net.minecraft.entity.EntityList#getEntityString
     * EntityList.getEntityString}).</dd>
     * <dd>If this entity has a custom name set, this will return that name.</dd>
     * <dd>For some entities, this will attempt to translate a nonexistent translation string; see <a
     * href="https://bugs.mojang.com/browse/MC-68446">MC-68446</a>. For {@linkplain
     * net.minecraft.entity.player.EntityPlayer#getName() players} this returns the player's name. For {@linkplain
     * net.minecraft.entity.passive.EntityOcelot ocelots} this may return the translation of
     * <code>entity.Cat.name</code> if it is tamed. For {@linkplain net.minecraft.entity.item.EntityItem#getName() item
     * entities}, this will attempt to return the name of the item in that item entity. In all cases other than players,
     * the custom name will overrule this.</dd>
     * <dd>For non-entity command senders, this will return some arbitrary name, such as "Rcon" or "Server".</dd>
     * </dl>
     */
    public String getName()
    {
        return "Server";
    }

    /**
     * Send a chat message to the CommandSender
     */
    public void sendMessage(ITextComponent component)
    {
        LOGGER.info(component.getUnformattedText());
    }

    /**
     * Returns {@code true} if the CommandSender is allowed to execute the command, {@code false} if not
     */
    public boolean canUseCommand(int permLevel, String commandName)
    {
        return true;
    }

    public ICommandManager getCommandManager()
    {
        return this.commandManager;
    }

    /**
     * Gets KeyPair instanced in MinecraftServer.
     */
    public KeyPair getKeyPair()
    {
        return this.serverKeyPair;
    }

    /**
     * Gets serverPort.
     */
    public int getServerPort()
    {
        return this.serverPort;
    }

    public void setServerPort(int port)
    {
        this.serverPort = port;
    }

    /**
     * Returns the username of the server owner (for integrated servers)
     */
    public String getServerOwner()
    {
        return this.serverOwner;
    }

    /**
     * Sets the username of the owner of this server (in the case of an integrated server)
     */
    public void setServerOwner(String owner)
    {
        this.serverOwner = owner;
    }

    public boolean isSinglePlayer()
    {
        return this.serverOwner != null;
    }

    public String getFolderName()
    {
        return this.folderName;
    }

    public void setFolderName(String name)
    {
        this.folderName = name;
    }

    public void setKeyPair(KeyPair keyPair)
    {
        this.serverKeyPair = keyPair;
    }

    public void setDifficultyForAllWorlds(EnumDifficulty difficulty)
    {
        for (WorldServer worldserver1 : this.worlds)
        {
            if (worldserver1 != null)
            {
                if (worldserver1.getWorldInfo().isHardcoreModeEnabled())
                {
                    worldserver1.getWorldInfo().setDifficulty(EnumDifficulty.HARD);
                    worldserver1.setAllowedSpawnTypes(true, true);
                }
                else if (this.isSinglePlayer())
                {
                    worldserver1.getWorldInfo().setDifficulty(difficulty);
                    worldserver1.setAllowedSpawnTypes(worldserver1.getDifficulty() != EnumDifficulty.PEACEFUL, true);
                }
                else
                {
                    worldserver1.getWorldInfo().setDifficulty(difficulty);
                    worldserver1.setAllowedSpawnTypes(this.allowSpawnMonsters(), this.canSpawnAnimals);
                }
            }
        }
    }

    public boolean allowSpawnMonsters()
    {
        return true;
    }

    /**
     * Gets whether this is a demo or not.
     */
    public boolean isDemo()
    {
        return this.isDemo;
    }

    /**
     * Sets whether this is a demo or not.
     */
    public void setDemo(boolean demo)
    {
        this.isDemo = demo;
    }

    public void canCreateBonusChest(boolean enable)
    {
        this.enableBonusChest = enable;
    }

    public ISaveFormat getActiveAnvilConverter()
    {
        return this.anvilConverterForAnvilFile;
    }

    public String getResourcePackUrl()
    {
        return this.resourcePackUrl;
    }

    public String getResourcePackHash()
    {
        return this.resourcePackHash;
    }

    public void setResourcePack(String url, String hash)
    {
        this.resourcePackUrl = url;
        this.resourcePackHash = hash;
    }

    public void addServerStatsToSnooper(Snooper playerSnooper)
    {
        playerSnooper.addClientStat("whitelist_enabled", Boolean.valueOf(false));
        playerSnooper.addClientStat("whitelist_count", Integer.valueOf(0));

        if (this.playerList != null)
        {
            playerSnooper.addClientStat("players_current", Integer.valueOf(this.getCurrentPlayerCount()));
            playerSnooper.addClientStat("players_max", Integer.valueOf(this.getMaxPlayers()));
            playerSnooper.addClientStat("players_seen", Integer.valueOf(this.playerList.getAvailablePlayerDat().length));
        }

        playerSnooper.addClientStat("uses_auth", Boolean.valueOf(this.onlineMode));
        playerSnooper.addClientStat("gui_state", this.getGuiEnabled() ? "enabled" : "disabled");
        playerSnooper.addClientStat("run_time", Long.valueOf((getCurrentTimeMillis() - playerSnooper.getMinecraftStartTimeMillis()) / 60L * 1000L));
        playerSnooper.addClientStat("avg_tick_ms", Integer.valueOf((int)(MathHelper.average(this.tickTimeArray) * 1.0E-6D)));
        int l = 0;

        if (this.worlds != null)
        {
            for (WorldServer worldserver1 : this.worlds)
            {
                if (worldserver1 != null)
                {
                    WorldInfo worldinfo = worldserver1.getWorldInfo();
                    playerSnooper.addClientStat("world[" + l + "][dimension]", Integer.valueOf(worldserver1.provider.getDimensionType().getId()));
                    playerSnooper.addClientStat("world[" + l + "][mode]", worldinfo.getGameType());
                    playerSnooper.addClientStat("world[" + l + "][difficulty]", worldserver1.getDifficulty());
                    playerSnooper.addClientStat("world[" + l + "][hardcore]", Boolean.valueOf(worldinfo.isHardcoreModeEnabled()));
                    playerSnooper.addClientStat("world[" + l + "][generator_name]", worldinfo.getTerrainType().getName());
                    playerSnooper.addClientStat("world[" + l + "][generator_version]", Integer.valueOf(worldinfo.getTerrainType().getVersion()));
                    playerSnooper.addClientStat("world[" + l + "][height]", Integer.valueOf(this.buildLimit));
                    playerSnooper.addClientStat("world[" + l + "][chunks_loaded]", Integer.valueOf(worldserver1.getChunkProvider().getLoadedChunkCount()));
                    ++l;
                }
            }
        }

        playerSnooper.addClientStat("worlds", Integer.valueOf(l));
    }

    public void addServerTypeToSnooper(Snooper playerSnooper)
    {
        playerSnooper.addStatToSnooper("singleplayer", Boolean.valueOf(this.isSinglePlayer()));
        playerSnooper.addStatToSnooper("server_brand", this.getServerModName());
        playerSnooper.addStatToSnooper("gui_supported", GraphicsEnvironment.isHeadless() ? "headless" : "supported");
        playerSnooper.addStatToSnooper("dedicated", Boolean.valueOf(this.isDedicatedServer()));
    }

    /**
     * Returns whether snooping is enabled or not.
     */
    public boolean isSnooperEnabled()
    {
        return true;
    }

    public abstract boolean isDedicatedServer();

    public boolean isServerInOnlineMode()
    {
        return this.onlineMode;
    }

    public void setOnlineMode(boolean online)
    {
        this.onlineMode = online;
    }

    public boolean getPreventProxyConnections()
    {
        return this.preventProxyConnections;
    }

    public void setPreventProxyConnections(boolean p_190517_1_)
    {
        this.preventProxyConnections = p_190517_1_;
    }

    public boolean getCanSpawnAnimals()
    {
        return this.canSpawnAnimals;
    }

    public void setCanSpawnAnimals(boolean spawnAnimals)
    {
        this.canSpawnAnimals = spawnAnimals;
    }

    public boolean getCanSpawnNPCs()
    {
        return this.canSpawnNPCs;
    }

    /**
     * Get if native transport should be used. Native transport means linux server performance improvements and
     * optimized packet sending/receiving on linux
     */
    public abstract boolean shouldUseNativeTransport();

    public void setCanSpawnNPCs(boolean spawnNpcs)
    {
        this.canSpawnNPCs = spawnNpcs;
    }

    public boolean isPVPEnabled()
    {
        return this.pvpEnabled;
    }

    public void setAllowPvp(boolean allowPvp)
    {
        this.pvpEnabled = allowPvp;
    }

    public boolean isFlightAllowed()
    {
        return this.allowFlight;
    }

    public void setAllowFlight(boolean allow)
    {
        this.allowFlight = allow;
    }

    /**
     * Return whether command blocks are enabled.
     */
    public abstract boolean isCommandBlockEnabled();

    public String getMOTD()
    {
        return this.motd;
    }

    public void setMOTD(String motdIn)
    {
        this.motd = motdIn;
    }

    public int getBuildLimit()
    {
        return this.buildLimit;
    }

    public void setBuildLimit(int maxBuildHeight)
    {
        this.buildLimit = maxBuildHeight;
    }

    public boolean isServerStopped()
    {
        return this.serverStopped;
    }

    public PlayerList getPlayerList()
    {
        return this.playerList;
    }

    public void setPlayerList(PlayerList list)
    {
        this.playerList = list;
    }

    /**
     * Sets the game type for all worlds.
     */
    public void setGameType(GameType gameMode)
    {
        for (WorldServer worldserver1 : this.worlds)
        {
            worldserver1.getWorldInfo().setGameType(gameMode);
        }
    }

    public NetworkSystem getNetworkSystem()
    {
        return this.networkSystem;
    }

    public boolean getGuiEnabled()
    {
        return false;
    }

    /**
     * On dedicated does nothing. On integrated, sets commandsAllowedForAll, gameType and allows external connections.
     */
    public abstract String shareToLAN(GameType type, boolean allowCheats);

    public int getTickCounter()
    {
        return this.tickCounter;
    }

    public void enableProfiling()
    {
        this.startProfiling = true;
    }

    /**
     * Get the world, if available. <b>{@code null} is not allowed!</b> If you are not an entity in the world, return
     * the overworld
     */
    public World getEntityWorld()
    {
        return this.worlds[0];
    }

    /**
     * Return the spawn protection area's size.
     */
    public int getSpawnProtectionSize()
    {
        return 16;
    }

    public boolean isBlockProtected(World worldIn, BlockPos pos, EntityPlayer playerIn)
    {
        return false;
    }

    /**
     * Set the forceGamemode field (whether joining players will be put in their old gamemode or the default one)
     */
    public void setForceGamemode(boolean force)
    {
        this.isGamemodeForced = force;
    }

    /**
     * Get the forceGamemode field (whether joining players will be put in their old gamemode or the default one)
     */
    public boolean getForceGamemode()
    {
        return this.isGamemodeForced;
    }

    public Proxy getServerProxy()
    {
        return this.serverProxy;
    }

    public static long getCurrentTimeMillis()
    {
        return System.currentTimeMillis();
    }

    public int getMaxPlayerIdleMinutes()
    {
        return this.maxPlayerIdleMinutes;
    }

    public void setPlayerIdleTimeout(int idleTimeout)
    {
        this.maxPlayerIdleMinutes = idleTimeout;
    }

    public MinecraftSessionService getMinecraftSessionService()
    {
        return this.sessionService;
    }

    public GameProfileRepository getGameProfileRepository()
    {
        return this.profileRepo;
    }

    public PlayerProfileCache getPlayerProfileCache()
    {
        return this.profileCache;
    }

    public ServerStatusResponse getServerStatusResponse()
    {
        return this.statusResponse;
    }

    public void refreshStatusNextTick()
    {
        this.nanoTimeSinceStatusRefresh = 0L;
    }

    @Nullable
    public Entity getEntityFromUuid(UUID uuid)
    {
        for (WorldServer worldserver1 : this.worlds)
        {
            if (worldserver1 != null)
            {
                Entity entity = worldserver1.getEntityFromUuid(uuid);

                if (entity != null)
                {
                    return entity;
                }
            }
        }

        return null;
    }

    /**
     * Returns true if the command sender should be sent feedback about executed commands
     */
    public boolean sendCommandFeedback()
    {
        return this.worlds[0].getGameRules().getBoolean("sendCommandFeedback");
    }

    /**
     * Get the Minecraft server instance
     */
    public MinecraftServer getServer()
    {
        return this;
    }

    public int getMaxWorldSize()
    {
        return 29999984;
    }

    public <V> ListenableFuture<V> callFromMainThread(Callable<V> callable)
    {
        Validate.notNull(callable);

        if (!this.isCallingFromMinecraftThread() && !this.isServerStopped())
        {
            ListenableFutureTask<V> listenablefuturetask = ListenableFutureTask.<V>create(callable);

            synchronized (this.futureTaskQueue)
            {
                this.futureTaskQueue.add(listenablefuturetask);
                return listenablefuturetask;
            }
        }
        else
        {
            try
            {
                return Futures.<V>immediateFuture(callable.call());
            }
            catch (Exception exception)
            {
                return Futures.immediateFailedCheckedFuture(exception);
            }
        }
    }

    public ListenableFuture<Object> addScheduledTask(Runnable runnableToSchedule)
    {
        Validate.notNull(runnableToSchedule);
        return this.<Object>callFromMainThread(Executors.callable(runnableToSchedule));
    }

    public boolean isCallingFromMinecraftThread()
    {
        return Thread.currentThread() == this.serverThread;
    }

    /**
     * The compression treshold. If the packet is larger than the specified amount of bytes, it will be compressed
     */
    public int getNetworkCompressionThreshold()
    {
        return 256;
    }

    public long getCurrentTime()
    {
        return this.currentTime;
    }

    public Thread getServerThread()
    {
        return this.serverThread;
    }

    public int getSpawnRadius(@Nullable WorldServer worldIn)
    {
        return worldIn != null ? worldIn.getGameRules().getInt("spawnRadius") : 10;
    }

    public AdvancementManager getAdvancementManager()
    {
        return this.worlds[0].getAdvancementManager();
    }

    public FunctionManager getFunctionManager()
    {
        return this.worlds[0].getFunctionManager();
    }

    public void reload()
    {
        if (this.isCallingFromMinecraftThread())
        {
            CarpetServer.rsmmServer.getMultimeter().reloadOptions(); // RSMM
            this.getPlayerList().saveAllPlayerData();
            this.worlds[0].getLootTableManager().reloadLootTables();
            this.getAdvancementManager().reload();
            this.getFunctionManager().reload();
            this.getPlayerList().reloadResources();
        }
        else
        {
            this.addScheduledTask(this::reload);
        }
    }

    // RSMM
    public boolean isPaused() {
        return false;
    }
}