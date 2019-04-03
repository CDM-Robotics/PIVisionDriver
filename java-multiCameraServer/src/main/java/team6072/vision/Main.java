
/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018 FIRST. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

package team6072.vision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import edu.wpi.cscore.VideoSource;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionPipeline;
import edu.wpi.first.vision.VisionThread;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;

import org.opencv.core.Mat;

/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ]
           }
       ]
   }
 */

public final class Main {

    private static String CONFIGFILE = "/boot/frc.json";

    @SuppressWarnings("MemberName")
    public static class CameraConfig {
        public String name;
        public String path;
        public JsonObject config;
    }

    public static int team;
    public static boolean server;
    public static List<CameraConfig> cameraConfigs = new ArrayList<>();
    public static NetworkTableInstance mTableInstance;
    public static NetworkTable mTbl;
    public static ScheduledExecutorService mScheduler;

    
    protected static UpdateCameraMaster updateCameraMaster;

    // private constructor
    private Main() {
    }

    /**
     * Main.
     */
    public static void main(String... args) {
        if (args.length > 0) {
            CONFIGFILE = args[0];
        }

        // start NetworkTables
        NetworkTableInstance mTableInstance = NetworkTableInstance.getDefault();
        // read configuration

        // Change camera configuration!!!!!
        if (!readConfig()) {
            return;
        }

        if (server) {
            System.out.println("Setting up NetworkTables server");
            mTableInstance.startServer();
        } else {
            System.out.println("Setting up NetworkTables client for team " + team);
            mTableInstance.startClientTeam(team);
        }

        // start cameras
        List<VideoSource> cameras = new ArrayList<>();
        for (CameraConfig cameraConfig : cameraConfigs) {
            cameras.add(startCamera(cameraConfig));
        }

        // start image processing on camera 0 if present
        System.out.println("Camera Number = " + cameras.size());
        Timestamper.getInstance();
        

        for(int i = 0; i < cameras.size(); i++){
            VisionThread visionThread = new VisionThread(cameras.get(i), new CloseUpPipeline(),
            new CloseUpPipelineListener(i + ""));
            visionThread.start();
        }

        updateCameraMaster = new UpdateCameraMaster();
        mScheduler = Executors.newScheduledThreadPool(1);
        mScheduler.scheduleWithFixedDelay(updateCameraMaster, 30, 30, TimeUnit.MILLISECONDS);
    }

    
    protected static class UpdateCameraMaster implements Runnable {
        @Override
        public void run(){
            CameraMaster.getInstance().updateNetworkTables();
        }
    }

    /**
     * Read configuration file. Return FALSE if fail
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    public static boolean readConfig() {
        // parse file
        JsonElement top;
        try {
            top = new JsonParser().parse(Files.newBufferedReader(Paths.get(CONFIGFILE)));
        } catch (IOException ex) {
            System.err.println("could not open '" + CONFIGFILE + "': " + ex);
            return false;
        }

        // top level must be an object
        if (!top.isJsonObject()) {
            parseError("must be JSON object");
            return false;
        }
        JsonObject obj = top.getAsJsonObject();

        // team number
        JsonElement teamElement = obj.get("team");
        if (teamElement == null) {
            parseError("could not read team number");
            return false;
        }
        team = teamElement.getAsInt();

        // ntmode (optional)
        if (obj.has("ntmode")) {
            String str = obj.get("ntmode").getAsString();
            // if ("client".equalsIgnoreCase(str)) {
            //     server = false;
            // } else if ("server".equalsIgnoreCase(str)) {
            //     server = true;
            // } else {
            //     parseError("could not understand ntmode value '" + str + "'");
            // }
            server = false;
        }

        // cameras
        JsonElement camerasElement = obj.get("cameras");
        if (camerasElement == null) {
            parseError("could not read cameras");
            return false;
        }
        JsonArray cameras = camerasElement.getAsJsonArray();
        for (JsonElement camera : cameras) {
            if (!readCameraConfig(camera.getAsJsonObject())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Report parse error.
     */
    public static void parseError(String str) {
        System.err.println("config error in '" + CONFIGFILE + "': " + str);
    }

    /**
     * Read single camera configuration.
     */
    public static boolean readCameraConfig(JsonObject config) {
        CameraConfig cam = new CameraConfig();

        // name
        JsonElement nameElement = config.get("name");
        if (nameElement == null) {
            parseError("could not read camera name");
            return false;
        }
        cam.name = nameElement.getAsString();
        
        // path
        JsonElement pathElement = config.get("path");
        if (pathElement == null) {
            parseError("camera '" + cam.name + "': could not read path");
            return false;
        }
        cam.path = pathElement.getAsString();

        cam.config = config;

        cameraConfigs.add(cam);
        return true;
    }

    /**
     * Start running the camera.
     */
    public static VideoSource startCamera(CameraConfig config) {
        System.out.println("Starting camera '" + config.name + "' on " + config.path);
        VideoSource camera = CameraServer.getInstance().startAutomaticCapture(config.name, config.path);
        Gson gson = new GsonBuilder().create();
        camera.setConfigJson(gson.toJson(config.config));
        return camera;
    }

}

