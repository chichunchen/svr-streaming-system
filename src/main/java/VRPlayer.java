import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.google.gson.Gson;
import sun.rmi.runtime.Log;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Vector;

/**
 * This class manage frame rendering, video segment downloading, and all bunch
 * of fov-logic.
 */
public class VRPlayer {
    private static final int SEGMENT_START_NUM = 1;
    private static final String MANIFEST_PATH = "client-full.txt";
    private static final String bucketName = "vros-video-segments";
    private static final int FRAME_PER_VIDEO_SEGMENT = 15;

    private String host;
    private int port;
    private String segmentPath;
    private int currSegId;      // indicate the top video segment id could be decoded
    private String fullSegmentDir;
    private String fovSegmentDir;
    private VideoSegmentManifest manifest;
    private FOVTraces fovTraces;    // use currSegId to extract fov from fovTraces
    private AmazonS3 s3;
    private Logger logger;

    /**
     * Construct a VRPlayer object which manage GUI, video segment downloading, and video segment decoding
     *
     * @param host        Host of VRServer.
     * @param port        Port of VRServer.
     * @param segmentPath Path to the storage of video segments in a temporary path like tmp/.
     * @param name        Name of the video.
     */
    public VRPlayer(String host, int port, String segmentPath, String name, Utilities.Mode mode, Logger logger) {
        // init vars
        this.host = host;
        this.port = port;
        this.segmentPath = segmentPath;
        this.currSegId = SEGMENT_START_NUM;
        this.fovTraces = new FOVTraces(name + "-trace.txt");
        this.s3 = new AmazonS3Client();
        this.s3.setRegion(Region.getRegion(Regions.US_EAST_1));
        this.fullSegmentDir = name + "-full";
        this.fovSegmentDir = name + "-fov";
        String manifestFileName = name + "-manifest.txt";
        this.logger = logger;

        File segmentDir = new File(segmentPath);
        if (!segmentDir.exists()) {
            segmentDir.mkdirs();
        }

        downloadFileFromS3ToFileSystem(manifestFileName, MANIFEST_PATH);
        parseManifest();

        switch (mode) {
            case BASELINE:
                BaselineNetworkHandler();
                break;
            case SVR:
                SVRNetworkHandler();
                logger.printProtocol("[STEP 0-2] Receive manifest from VRServer");
                break;
            default:
                logger.printErr("Should specify mode SVR or BASELINE");
                System.exit(1);
        }
    }

    private void downloadFileFromS3ToFileSystem(String key, String out) {
        S3Object s3Object = s3.getObject(new GetObjectRequest(bucketName, key));
        InputStream in = s3Object.getObjectContent();
        try {
            Files.copy(in, Paths.get(out), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void parseManifest() {
        Gson gson = new Gson();
        try {
            BufferedReader bufferedReader = new BufferedReader(new FileReader(VRPlayer.MANIFEST_PATH));
            manifest = gson.fromJson(bufferedReader, VideoSegmentManifest.class);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void BaselineNetworkHandler() {
        for (int currSegId = 1; currSegId <= manifest.getVideoSegmentAmount(); currSegId++) {
            String s3videoFileName = getS3KeyName(FOVProtocol.FULL);
            String clientVideoFilename = Utilities.getClientFullSegmentName(segmentPath, currSegId);
            downloadFileFromS3ToFileSystem(s3videoFileName, clientVideoFilename);
            new PlayNative(clientVideoFilename, 0, -1);
            logger.printProtocol("[STEP 1] SEGMENT #" + currSegId);
        }
    }

    private String getS3KeyName(int predPathMsg) {
        String videoFileName;
        if (predPathMsg == FOVProtocol.FULL) {
            videoFileName = Utilities.getServerFullSizeSegmentName(fullSegmentDir, "output", currSegId);
        } else {
            videoFileName = Utilities.getServerFOVSegmentName(fovSegmentDir, currSegId, predPathMsg);
        }
        return videoFileName;
    }

    /**
     * Download video segments following svr fov protocol.
     */
    private void SVRNetworkHandler() {
        while (currSegId <= manifest.getVideoSegmentAmount()) {
            // 1. request fov with the key frame metadata from VRServer
            int keyFrameID = (currSegId - 1) * FRAME_PER_VIDEO_SEGMENT;
            TCPSerializeSender metadataRequest = new TCPSerializeSender<>(host, port, fovTraces.get(keyFrameID));
            metadataRequest.request();
            logger.printProtocol("[STEP 1] SEGMENT #" + currSegId + " send metadata to server");

            // 2. get response from VRServer which indicate "FULL" or "FOV"
            TCPSerializeReceiver msgReceiver = new TCPSerializeReceiver<Integer>(host, port);
            msgReceiver.request();
            int predPathMsg = (Integer) msgReceiver.getSerializeObj();
            logger.printProtocol("[STEP 4] get size message: " + FOVProtocol.print(predPathMsg));

            // 3-1. check whether the other video frames (exclude key frame) does not match fov
            // 3-2. if any frame does not match, request full size video segment from VRServer with "BAD"
            // 3-2  if all the frames matches, send back "GOOD"
            if (FOVProtocol.isFOV(predPathMsg)) {
                logger.printProtocol("[STEP 6] download video segment from VRServer");
                String s3videoFileName = getS3KeyName(predPathMsg);
                String clientVideoFilename = Utilities.getClientFOVSegmentName(segmentPath, currSegId, predPathMsg);

                logger.startLogTime();
                downloadFileFromS3ToFileSystem(s3videoFileName, clientVideoFilename);
                logger.endLogAndPrint();

                // compare all the user-fov frames exclude for key frame with the predicted fov
                Vector<FOVMetadata> pathMetadataVec = manifest.getPredMetaDataVec().get(currSegId).getPathVec();
                FOVMetadata pathMetadata = pathMetadataVec.get(predPathMsg);
                int secondDownloadMsg = FOVProtocol.GOOD;
                int totalDecodedFrame = 0;

                // Iterate fov until fovTrace not match
                for (int i = 0; i < FRAME_PER_VIDEO_SEGMENT; i++) {
                    FOVMetadata userFov = fovTraces.get(keyFrameID);
                    double coverRatio = pathMetadata.getOverlapRate(userFov);
                    if (coverRatio < FOVProtocol.THRESHOLD) {
                        logger.printProtocol("[DEBUG] fail at keyFrameID: " + keyFrameID);
                        logger.printProtocol("[DEBUG] user fov: " + userFov);
                        logger.printProtocol("[DEBUG] path metadata: " + pathMetadata);
                        logger.printProtocol("[DEBUG] overlap ratio: " + coverRatio);
                        secondDownloadMsg = FOVProtocol.BAD;
                        break;
                    } else {
                        keyFrameID++;
                        totalDecodedFrame++;
                    }
                }
                new PlayNative(clientVideoFilename, 0, totalDecodedFrame - 1);

                // notify good/bad
                System.out.println("[STEP 7] " + FOVProtocol.print(secondDownloadMsg));

                // receive full size video segment if send back BAD
                if (secondDownloadMsg == FOVProtocol.BAD) {
                    logger.printProtocol("[STEP 10] Download full size video segment from VRServer");
                    s3videoFileName = getS3KeyName(FOVProtocol.FULL);
                    clientVideoFilename = Utilities.getClientFullSegmentName(segmentPath, currSegId);

                    logger.startLogTime();
                    downloadFileFromS3ToFileSystem(s3videoFileName, clientVideoFilename);
                    logger.endLogAndPrint();

                    logger.printProtocol("[DEBUG] Start decode from frame: " + totalDecodedFrame);
                    new PlayNative(clientVideoFilename, totalDecodedFrame, -1);
                }
            } else if (FOVProtocol.isFull(predPathMsg)) {
                logger.printProtocol("[STEP 6] download video segment from VRServer");
                String s3videoFileName = getS3KeyName(FOVProtocol.FULL);
                String clientVideoFilename = Utilities.getClientFullSegmentName(segmentPath, currSegId);

                logger.startLogTime();
                downloadFileFromS3ToFileSystem(s3videoFileName, clientVideoFilename);
                logger.endLogAndPrint();
                new PlayNative(clientVideoFilename, 0, -1);
            } else {
                // should never go here
                assert (false);
            }

            logger.printProtocol("---------------------------------------------------------");
            currSegId++;
        }
    }

    /**
     * Example: java VRPlayer localhost 1988 tmp rhino SVR
     *
     * @param args command line args.
     */
    public static void main(String[] args) {
        Logger logger = new Logger(false, true, false);
        VRPlayer vrPlayer = new VRPlayer(args[0],
                Integer.parseInt(args[1]),
                args[2],
                args[3],
                Utilities.string2mode(args[4]),
                logger);
    }
}
