package thallium.fabric.mixins;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import com.mojang.blaze3d.systems.RenderSystem;

import thallium.fabric.interfaces.IThreadExecutor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.screen.Overlay;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SplashScreen;
import net.minecraft.client.options.CloudRenderMode;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.Option;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.Util;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClient {

    // Shadow Fields
    @Shadow public Window window;
    @Shadow public boolean paused;
    @Shadow public int fpsCounter;
    @Shadow public static int currentFps;
    @Shadow public GameOptions options;
    @Shadow public String fpsDebugString;
    @Shadow public long nextDebugInfoUpdateTime;
    @Shadow private CompletableFuture<Void> resourceReloadFuture;
    @Shadow public Screen currentScreen;
    @Shadow public Overlay overlay;
    @Shadow private Queue<Runnable> renderTaskQueue;
    @Shadow private RenderTickCounter renderTickCounter;
    @Shadow private float pausedTickDelta;
    @Shadow private IntegratedServer server;
    @Shadow private SoundManager soundManager;
    @Shadow public GameRenderer gameRenderer;
    @Shadow public Framebuffer framebuffer;
    @Shadow public boolean skipGameRender;
    @Shadow public ToastManager toastManager;

    // Shadow Methods
    @Shadow public boolean isIntegratedServerRunning() {return false;}
    @Shadow public int getFramerateLimit() {return 0;}
    @Shadow public void scheduleStop() {}
    @Shadow public void tick() {}
    @Shadow public CompletableFuture<Void> reloadResources() {return null;}


    private boolean doUpdate = false;
    private long last;

    private int a = 0;
    private int c = 0;

    private long lastFpsUpdateTime;

    @Overwrite
    private void render(boolean tick) {
        this.window.setPhase("Pre render");

        long l = Util.getMeasuringTimeNano();
        if (this.window.shouldClose())
            this.scheduleStop();

        if (this.resourceReloadFuture != null && !(this.overlay instanceof SplashScreen)) {
            CompletableFuture<Void> completableFuture = this.resourceReloadFuture;
            this.resourceReloadFuture = null;
            this.reloadResources().thenRun(() -> completableFuture.complete(null));
        }
        Runnable runnable;
        while ((runnable = this.renderTaskQueue.poll()) != null) runnable.run();

        if (tick) {
            int i = this.renderTickCounter.beginRenderTick(Util.getMeasuringTimeMs());
            ((IThreadExecutor)(Object)this).runTasks();
            for (int j = 0; j < Math.min(10, i); ++j)
                this.tick();
        }

        this.window.setPhase("Render");
        this.soundManager.updateListenerPosition(this.gameRenderer.getCamera());
        RenderSystem.pushMatrix();
        RenderSystem.clear(16640, false);
        this.framebuffer.beginWrite(true);
        BackgroundRenderer.method_23792();
        RenderSystem.enableTexture();
        RenderSystem.enableCull();

        // Also check our doUpdate boolean to keep backwards-compatibility with the vanilla behavior 
        if (!this.skipGameRender && doUpdate) {
            this.gameRenderer.render(this.paused ? this.pausedTickDelta : this.renderTickCounter.tickDelta, l, tick);
            this.toastManager.draw(new MatrixStack());
        }

        this.framebuffer.endWrite();
        RenderSystem.popMatrix();
        RenderSystem.pushMatrix();

        this.framebuffer.draw(this.window.getFramebufferWidth(), this.window.getFramebufferHeight());

        RenderSystem.popMatrix();
        this.window.swapBuffers();

        int k = this.getFramerateLimit();
        if ((double)k < Option.FRAMERATE_LIMIT.getMax()) RenderSystem.limitDisplayFPS(k);

        this.window.setPhase("Post render");
        ++this.fpsCounter;
        boolean bl = this.isIntegratedServerRunning() && (this.currentScreen != null && this.currentScreen.isPauseScreen() || this.overlay != null && this.overlay.pausesGame()) && !this.server.isRemote();
        if (this.paused != bl) {
            if (this.paused)
                 this.pausedTickDelta = this.renderTickCounter.tickDelta;
            else this.renderTickCounter.tickDelta = this.pausedTickDelta;
            this.paused = bl;
        }

        // Thallium - Replaced while loop
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFpsUpdateTime >= 1000) {
            lastFpsUpdateTime = currentTime;
            currentFps = this.fpsCounter;
            this.fpsDebugString = String.format("%d fps T: %s%s%s%s B: %d. +Thallium", currentFps, (double)this.options.maxFps == Option.FRAMERATE_LIMIT.getMax() ? "inf" : Integer.valueOf(this.options.maxFps), this.options.enableVsync ? " vsync" : "", this.options.graphicsMode.toString(), this.options.cloudRenderMode == CloudRenderMode.OFF ? "" : (this.options.cloudRenderMode == CloudRenderMode.FAST ? " fast-clouds" : " fancy-clouds"), this.options.biomeBlendRadius);
            this.fpsCounter = 0;
        }

        long took = (System.currentTimeMillis());
        int skip = 60;

        // Thallium - Stabilize large FPS
        if (currentFps > 500) skip = 50;
        if (currentFps > 580) skip = 40;
        if (currentFps > 650) skip = 30;
        if (currentFps > 900) skip = 25;
        if (currentFps > 980) skip = 20;
        if (currentFps > 1250) skip = 15;

        // Thallium - If FPS is low, lower amount of processing
        if (took - last > skip) {
            doUpdate = true;
            last = took;
            a++;
        } else {
            if (c == 0 && a < 50) {
                a++;
                doUpdate = true;
            } else doUpdate = false;
            c++;
            if (c > 10) c = 0;
        }
    }

}
