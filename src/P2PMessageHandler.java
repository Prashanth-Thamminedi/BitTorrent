import static java.lang.System.exit;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;

public class P2PMessageHandler {
    peerProcess host_peer;      // Current host
    PeerDetails neighbor_peer;  // Neighbor peer to which the current TCP is established
    Boolean chocked_by_host;    // Local choked state for each thread
    int latest_piece_ptr;       // Local pointer to the pending updates
    
    public P2PMessageHandler(peerProcess host_peer, PeerDetails neighbor_peer) {
        this.host_peer     = host_peer;
        this.neighbor_peer = neighbor_peer;
        this.chocked_by_host = true;
        this.latest_piece_ptr = host_peer.host_details.has_file ? host_peer.no_of_pieces : 0;
        InitializeNeighBitField();
    }

    // Initializing neigh bitfield to avoid null ptr exceptions in when no bitfield message is received
    private void InitializeNeighBitField() {
        int allocated_bits = (int) Math.ceil((float) host_peer.no_of_pieces/64) * 64;
        neighbor_peer.bitfield_piece_index = new BitSet(allocated_bits);
        neighbor_peer.bitfield_piece_index.set(host_peer.no_of_pieces);
    }

    // Method to handle BitField Message received from Neighbor
    public void HandleBitFieldMessage(Message message) {
        // Create an empty bitset of same length as payload
        BitSet peer_bitset                 = new BitSet(message.GetMessagePayload().length * 8); // 8 bits in a byte
        // Retrieve the message payload and store it in neighbor_peer object
        byte[] message_payload             = message.GetMessagePayload();
        boolean interested                 = false;

        // Set the peer bitfield from bitfield index payload
        for (int i = 0; i < message.GetMessageLength(); i++) {
            // Parse each byte of the bitfield message
            for (int j = 0; j < 8; j++) {
                if ((message_payload[i] & (1 << j)) != 0) {
                    peer_bitset.set(i * 8 + j);
                    // If the peer neighbor has any bits host does not have, flag interested to true
                    if(peer_bitset.get(i * 8 + j) && !host_peer.host_details.bitfield_piece_index.get(i * 8 + j)) {
                        interested = true;
                    }
                }
            }
        }

        // Set neighbor bit field
        neighbor_peer.bitfield_piece_index = peer_bitset;

        // The neighbor has file already, so mark the thread as complete and increment completed files
        host_peer.completed_peer_files += 1;
        host_peer.completed_threads++;

        // Send Interested if the above result is not empty else send NotInterested message
        MessageType msg_type = interested ? MessageType.INTERESTED : MessageType.NOTINTERESTED;

        // Make third argument in Message as None and avoid sending third argument?
        Message msg = new Message(msg_type, new byte[1]);
        Utils.sendMessage(msg.BuildMessageByteArray(), neighbor_peer.out);
    }

    // Mark as choked by neighbpr
    public void HandleChokeMessage() {
        host_peer.choked_by_neighbors.put(neighbor_peer.peer_id, true);
    }

    public void HandleUnChokeMessage() {
        host_peer.choked_by_neighbors.put(neighbor_peer.peer_id, false);
        // Gets next interested index and sends request message, re-requests corrupted messages
        int interested_index = Utils.GetInterestIndex(host_peer, neighbor_peer);
        if (interested_index != -1) {
            Message msg = new Message(MessageType.REQUEST, ByteBuffer.allocate(4).putInt(interested_index).array());
            Utils.sendMessage(msg.BuildMessageByteArray(), neighbor_peer.out);
            host_peer.requested_indices.add(interested_index);
        }else if(host_peer.host_details.bitfield_piece_index.nextClearBit(0) < host_peer.no_of_pieces) {
            Message messg = new Message(MessageType.REQUEST, ByteBuffer.allocate(4).putInt(host_peer.host_details.bitfield_piece_index.nextClearBit(0)).array());
            Utils.sendMessage(messg.BuildMessageByteArray(), neighbor_peer.out);
        }
    }

    // Mark interested neighbor
    public void HandleInterestedMessage() {
        host_peer.neighbors_interested_in_host.put(neighbor_peer.peer_id, true);
    }

    // Mark not interested neighbor
    public void HandleNotInterestedMessage() {
        host_peer.neighbors_interested_in_host.put(neighbor_peer.peer_id, false);
    }

    // Handler for 'have' message type
    public void HandleHaveMessage(Message message_received) {
        int bitfield_index = ByteBuffer.wrap(Arrays.copyOfRange(message_received.GetMessagePayload(), 0, 4)).getInt();;
        
        // Update neighbor and check if complete
        neighbor_peer.bitfield_piece_index.set(bitfield_index);
        if(Utils.CheckAllPiecesReceived(host_peer.neighbors_list.get(neighbor_peer.peer_id).bitfield_piece_index, host_peer.no_of_pieces)){
            // If Multiple haves from same neighbor - can cause early termination
            host_peer.completed_peer_files += 1;
        }

        // If the all have updates have been sent and host received file, increment thread completed count
        if(latest_piece_ptr == host_peer.no_of_pieces && host_peer.host_details.has_file && host_peer.completed_peer_files == host_peer.neighbors_list.size()){
            host_peer.completed_threads++;
        }

        // Check if interested
        boolean send_interested = Utils.CheckInterestInIndex(host_peer.host_details, neighbor_peer, bitfield_index);

        MessageType msg_type = send_interested ? MessageType.INTERESTED : MessageType.NOTINTERESTED;
        Message msg = new Message(msg_type, new byte[0]);
        Utils.sendMessage(msg.BuildMessageByteArray(), neighbor_peer.out);
    }

    public void HandleRequestMessage(Message message_received, int index) {
        // Don't send data when neighbor is choked
        if (!host_peer.unchoked_by_host.get(neighbor_peer.peer_id) && host_peer.opt_neighbor != neighbor_peer.peer_id)
            return;

        // Below should not happen as the request is received only if the host has required piece
        if (!host_peer.host_details.bitfield_piece_index.get(index))
            return;
        // Get the requested index in byte format
        byte[] message_payload = message_received.GetMessagePayload();

        // Pull the required piece
        byte[] requested_piece = host_peer.file_handler.GetPiece(index);

        // Below is to concatenate index in byte format and the piece
        byte[] data_payload = new byte[requested_piece.length + message_payload.length];

        ByteBuffer buffer = ByteBuffer.wrap(data_payload);
        buffer.put(message_payload);
        buffer.put(requested_piece);

        Message msg = new Message(MessageType.PIECE, buffer.array());
        Utils.sendMessage(msg.BuildMessageByteArray(), neighbor_peer.out);
    }
            
    public void HandlePieceMessage(Message message_received, int index) throws IOException {         
        // Below should not happen as the piece is sent only with a request and
        // request for a given index is made to a single neighbor
        if (host_peer.host_details.bitfield_piece_index.get(index))
            return;
            
        // Copy the piece and set it in the respective index
        byte[] piece_payload = Arrays.copyOfRange(message_received.GetMessagePayload(), 4,
        message_received.GetMessageLength());
        host_peer.file_handler.SetPiece(index, piece_payload);

        // (Broadcast) Add the piece index into the latest piece shared resource for all threads
        host_peer.host_details.latest_piece.add(index);
        host_peer.logger.log("has downloaded the piece " + index + " from " + neighbor_peer.peer_id + ". Now \r\n" + //
                "the number of pieces it has is " + host_peer.host_details.latest_piece.size());
        
        // Check if all pieces received and build the file
        if (Utils.CheckAllPiecesReceived(host_peer.host_details.bitfield_piece_index, host_peer.no_of_pieces)) {
            host_peer.file_handler.BuildFile();
            host_peer.host_details.has_file = true;
            host_peer.logger.log("has downloaded the complete file.");
            return;
        }

        // Request more pieces
        int interested_index = Utils.GetInterestIndex(host_peer, neighbor_peer);
        if (interested_index != -1) {
            Message messg = new Message(MessageType.REQUEST, ByteBuffer.allocate(4).putInt(interested_index).array());
            Utils.sendMessage(messg.BuildMessageByteArray(), neighbor_peer.out);
            host_peer.requested_indices.add(interested_index);
        } else if(host_peer.host_details.bitfield_piece_index.nextClearBit(0) < host_peer.no_of_pieces) {
            Message messg = new Message(MessageType.REQUEST, ByteBuffer.allocate(4).putInt(host_peer.host_details.bitfield_piece_index.nextClearBit(0)).array());
            Utils.sendMessage(messg.BuildMessageByteArray(), neighbor_peer.out);
        }
    }

    public void SendUnChokedMessage() {
        Message msg = new Message(MessageType.UNCHOKE, new byte[1]);
        Utils.sendMessage(msg.BuildMessageByteArray(), neighbor_peer.out);
    }
    
    public void SendChokedMessage() {
        Message msg = new Message(MessageType.CHOKE, new byte[1]);
        Utils.sendMessage(msg.BuildMessageByteArray(), neighbor_peer.out);
    }

    public void RelayHaveMessages() {
        // Read the size of global received pieces
        int n = host_peer.host_details.latest_piece.size();
        // Get pointer to the next expected piece
        int i = latest_piece_ptr;
        while(i < n) {
            // Send have messages for all the new pieces received
            Message msg = new Message(MessageType.HAVE, ByteBuffer.allocate(4).putInt(host_peer.host_details.latest_piece.get(i)).array());
            Utils.sendMessage(msg.BuildMessageByteArray(), neighbor_peer.out);
            i++;
        }
        // Update latest piece pointer
        latest_piece_ptr = i;
    }
    
    public void CheckTermination() {
        // If all the threads are complete, terminate      
        if(host_peer.completed_threads == host_peer.neighbors_list.size()) {
            exit(0);
        }
    }

    private void ProcessMessage(Message message_received) throws IOException {
        MessageType msg_type = message_received.GetMessageType();
         switch(msg_type) {
            case CHOKE: {
                host_peer.logger.log("is choked by" + neighbor_peer.peer_id);
                HandleChokeMessage();
                break;
            }
            case UNCHOKE: {
                host_peer.logger.log("is unchoked by " + neighbor_peer.peer_id);
                HandleUnChokeMessage();
                break;
            }
            case INTERESTED: {
                host_peer.logger.log("received the 'interested' message from " + neighbor_peer.peer_id);
                HandleInterestedMessage();
                break;
            }
            case NOTINTERESTED: {
                host_peer.logger.log("received 'not interested' message from " + neighbor_peer.peer_id);
                HandleNotInterestedMessage();
                break;
            }
            case HAVE: {
                int index = ByteBuffer.wrap(Arrays.copyOfRange(message_received.GetMessagePayload(), 0, 4)).getInt();;
                host_peer.logger.log("received the 'have' message from " + neighbor_peer.peer_id + " for the piece " + index);
                HandleHaveMessage(message_received);
                break;
            }
            case BITFIELD: {
                // host_peer.logger.log("received " + msg_type.toString() + " message from " + neighbor_peer.peer_id);
                HandleBitFieldMessage(message_received);
                break;
            }
            case REQUEST: {
                int index = ByteBuffer.wrap(Arrays.copyOfRange(message_received.GetMessagePayload(), 0, 4)).getInt();
                // host_peer.logger.log("received " + msg_type.toString() + " (" + index + ") message from " + neighbor_peer.peer_id);
                HandleRequestMessage(message_received, index);
                break;
            }
            case PIECE: {
                int index = ByteBuffer.wrap(Arrays.copyOfRange(message_received.GetMessagePayload(), 0, 4)).getInt();
                // host_peer.logger.log("received " + msg_type.toString() + " (" + index + ") message from " + neighbor_peer.peer_id);
                HandlePieceMessage(message_received, index);
                break;
            }
            default: ;
        }
    }

    private void UpdateChokeUnchoke() {
        // Check if peer is the optimistically choked neighbor
        boolean is_peer_opt = neighbor_peer.peer_id == host_peer.opt_neighbor;

        // Check if peer is unchoked by host
        boolean is_peer_unchoked = host_peer.unchoked_by_host.getOrDefault(neighbor_peer.peer_id, false);

        // Check the present choke state in the current thread with global choke state
        if(chocked_by_host && ( is_peer_unchoked || is_peer_opt )) {
            chocked_by_host = false;
            SendUnChokedMessage();
        } else if(!chocked_by_host && !is_peer_unchoked && !is_peer_opt ) {
            chocked_by_host = true;
            SendChokedMessage();
        }
    }
    
    public void MessageListener() throws IOException {
        DataInputStream in = neighbor_peer.in;
        while (true) {
            
            // Check if all peers received file and terminates
            CheckTermination();
            
            // Check if the neighbor should be CHOKED or UNCHOKED
            UpdateChokeUnchoke();
            
            // Check for any new pieces received
            if(latest_piece_ptr < host_peer.host_details.latest_piece.size()) {
                RelayHaveMessages();
            }

            // Receive message and retrieve the message type
            if(in.available() != 0) {
                int bytes_available = in.available();
                byte[] recvd_message = new byte[bytes_available];
                in.read(recvd_message);

                // Parse the input stream for messages
                for(int i = 0; i < bytes_available;) {
                    int curr_msg_len = ByteBuffer.wrap(Arrays.copyOfRange(recvd_message, i, i + 4)).getInt();
                    Message curr_messg = new Message(ByteBuffer.wrap(Arrays.copyOfRange(recvd_message, i, i + 5 + curr_msg_len)).array());
                    // Take Action based on message type received
                    ProcessMessage(curr_messg);
                    i += 5 + curr_msg_len;
                }
            }
        }
    }
}
