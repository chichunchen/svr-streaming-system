import com.google.gson.*;

import java.io.*;
import java.util.Arrays;
import java.util.Vector;

/**
 * This class handles the creation and parsing of manifest files. The manifest file includes the file size of all the
 * video segments.
 */
public class Manifest implements Serializable {
    private int length;
    private Vector<VideoSegmentMetaData> predMetaDataVec;

    public class VideoSegmentMetaData {
        private Vector<FOVMetadata> pathVec;
        private long size;

        VideoSegmentMetaData(Vector<FOVMetadata> pathVec, long size) {
            this.pathVec = pathVec;
            this.size = size;
        }

        public Vector<FOVMetadata> getPathVec() {
            return pathVec;
        }
    }

    /**
     * Create a manifest object using all the video segment file size and the object-predicted trace file.
     * The filename of video segments in the storagePath should follow the pattern: storagePath/name_{num}.mp4
     *
     * @param storagePath     should be a path to a directory.
     * @param predFilePath    path to a object detection file of the video.
     */
    public Manifest(String storagePath, String predFilePath) {
        Vector<Vector<FOVMetadata>> fovMetadata2DVec = parsePredFile(predFilePath);

        // get video segment size and feed into
        File storageDirectory = new File(storagePath);
        predMetaDataVec = new Vector<>();
        this.length = 0;

        // padding
        predMetaDataVec.add(new VideoSegmentMetaData(null, -1L));

        // TODO now iterate whole file with the name rhino/output_xx.mp4, but we have rhino/1/3.mp4
        // TODO manifest should have the file size of all of the video segment includi
        if (storageDirectory.exists() && storageDirectory.isDirectory()) {
            File[] dirList = storageDirectory.listFiles();
            assert dirList != null;
            Arrays.sort(dirList, (f1, f2) -> {
                String f1name = f1.getName();
                String f2name = f2.getName();
                return Utilities.getIdFromFullSizeSegmentName(f1name) - Utilities.getIdFromFullSizeSegmentName(f2name);
            });
            for (File f : dirList) {
                int size = predMetaDataVec.size();
                predMetaDataVec.add(new VideoSegmentMetaData(fovMetadata2DVec.get(size), f.length()));
            }
        } else {
            System.err.println(storagePath + " should be a directory!");
            System.exit(1);
        }

        this.length = predMetaDataVec.size();
    }

    private Vector<Vector<FOVMetadata>> parsePredFile(String predFileName) {
        File predFile = new File(predFileName);
        Vector<Vector<FOVMetadata>> fovMetadata2DVec = new Vector<>();

        // parse predict file into fovMetadata2DVec.
        fovMetadata2DVec.add(null);
        if (predFile.exists()) {
            try {
                FileReader fileReader = new FileReader(predFile);
                BufferedReader bufferedReader = new BufferedReader(fileReader);
                String line;
                Vector<FOVMetadata> fovMetadataVec = new Vector<>();
                int last_id = 1;
                while ((line = bufferedReader.readLine()) != null) {
                    String[] columns = line.split("\\s");
                    int id = Integer.parseInt(columns[0]);
                    int pathId = Integer.parseInt(columns[1]);

                    if (pathId == 0) {
                        if (id == last_id) {
                            last_id = id;
                        } else  {
                            fovMetadata2DVec.add(fovMetadataVec);
                            fovMetadataVec = new Vector<>();
                        }
                    }
                    fovMetadataVec.add(new FOVMetadata(line, FOVProtocol.FOV_SIZE_WIDTH, FOVProtocol.FOV_SIZE_HEIGHT));
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
        return fovMetadata2DVec;
    }

    /**
     * Write manifest to the specified file.
     *
     * @param path the path of the manifest file.
     * @throws FileNotFoundException        when the path not exists.
     * @throws UnsupportedEncodingException when utf8 not supported.
     */
    public void write(String path) throws FileNotFoundException, UnsupportedEncodingException {
        PrintWriter writer = new PrintWriter(path, "UTF-8");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        writer.write(gson.toJson(this));
        writer.flush();
        writer.close();
    }

    /**
     * Get the file length of a specified video segment.
     *
     * @param i identifier of video segment.
     * @return size of video segment.
     */
    public long getVideoSegmentLength(int i) {
        assert (i > 0);
        return predMetaDataVec.get(i).size;
    }

    /**
     * Get the total number of video segments.
     *
     * @return the total of video segments.
     */
    public int getVideoSegmentAmount() {
        return predMetaDataVec.size() - 1;
    }


    @Override
    public String toString() {
        return "Manifest{" +
                "length=" + length +
                ", predMetaDataVec=" + predMetaDataVec +
                '}';
    }

    public int getLength() {
        return length;
    }

    public Vector<VideoSegmentMetaData> getPredMetaDataVec() {
        return predMetaDataVec;
    }
}
