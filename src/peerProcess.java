import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;

import static java.lang.System.exit;

public class peerProcess {

    private static Integer peer_id;
    private static PeerDetails curr_peer;
    private static HashMap<String, String> config_params;
    private static HashMap<Integer, PeerDetails> neighbors_list;
    private static ArrayList<Integer> previous_neighbors_ids;
    private static PeerClient peer_client;
    private static PeerServer peer_server;
    private static BitSet bitfield_piece_index;
    public static Logger logger;

    public peerProcess(int id) {
        peer_id                = id;
        config_params          = new HashMap<>();
        neighbors_list         = new HashMap<>();
        previous_neighbors_ids = new ArrayList<>();
        logger                 = new Logger(peer_id.toString());
    }

    // Method to read common.cfg and store values in a hashmap
    public void ReadCommonCfg() {
        try {
            String line;
            String[] line_split;
            BufferedReader file = new BufferedReader(new FileReader("Common.cfg"));
            while((line = file.readLine()) != null) {
                line_split = line.split(" ");
                config_params.put(line_split[0], line_split[1]);
            }
        }
        catch (Exception ex) {
            System.out.println(ex.toString());
        }
    }

    // Method to read PeerInfo.cfg and store the peer information as PeerDetails object in a hashmap
    public void ReadPeerInfoCfg() {
        try {
            String line;
            int p_id;
            boolean found_peer = false;

            BufferedReader file = new BufferedReader(new FileReader("PeerInfo.cfg"));

            while((line = file.readLine()) != null) {
                // Peer information stored as PeerDetails object
                PeerDetails peer_details = new PeerDetails(line);
                p_id = Integer.parseInt(line.split(" ")[0]);
                if (!found_peer && p_id == peer_id) {
                    found_peer = true;
                    curr_peer  = peer_details;
                } else {
                    // Append previous_neighbors_ids only until we find current peer
                    if (!found_peer)
                        previous_neighbors_ids.add(p_id);
                    // All the neighbors information is stored in a hashmap
                    neighbors_list.put(p_id, peer_details);
                }
            }
        }
        catch (Exception ex) {
            System.out.println(ex.toString());
        }
    }

    // Method to Set the bit fields based on the file size and piece size
    public void SetBitField() {
        int file_size    = Integer.parseInt(config_params.get("FileSize"));
        int piece_size   = Integer.parseInt(config_params.get("PieceSize"));
        int no_of_pieces = (int) Math.ceil((double)file_size/piece_size);

        bitfield_piece_index = new BitSet(no_of_pieces);

        // Sets all bit values to 1 if has_file is true else the values will be 0 by default
        if(curr_peer.has_file) {
            for(int i = 0; i < no_of_pieces; i++) {
                bitfield_piece_index.set(i);
            }
        }
    }

    public static void main(String args[]) {
        if (args.length < 1) {
            System.out.println("No arguments Passed. Exiting the Program");
            exit(1);
        }
        peerProcess peer = new peerProcess(Integer.parseInt(args[0]));
        // Read Common.cfg file
        peer.ReadCommonCfg();
        // Read PeerInfo.cfg file
        peer.ReadPeerInfoCfg();
        peer.SetBitField();
        
        // Creating PeerClient and PeerServer object
        peer_client = new PeerClient(peer_id, neighbors_list, previous_neighbors_ids, bitfield_piece_index, logger);
        peer_server = new PeerServer(peer_id, curr_peer.peer_port, neighbors_list, previous_neighbors_ids, bitfield_piece_index, logger);
        peer_client.start();
        peer_server.start();
    }

}
