package org.uet.dse.neo4j.collaboration;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import java.util.Map;

public class ConsensusService {
    private static ConsensusService instance;
    private ConsensusService() {}
    public static synchronized ConsensusService getInstance() {
        if (instance == null) instance = new ConsensusService();
        return instance;
    }

    public enum VoteType { APPROVE, REJECT, POSTPONE }

    public void heartbeat() {
        String myUser = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            session.run("MATCH (u:SessionUser {name: $name}) SET u.lastActive = timestamp(), u.status = 'ONLINE'",
                    Map.of("name", myUser));
        } catch (Exception ignored) {}
    }

    public Record getLatestPendingProposal() {
        String myUser = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            // Tìm Proposal PENDING mà mình chưa VOTE
            String cypher = "MATCH (p:ModelProposal {status: 'PENDING'}) " +
                    "WHERE p.proposer <> $me " +
                    "AND NOT EXISTS { MATCH (u:SessionUser {name: $me})-[:VOTED]->(p) } " +
                    "RETURN p.id as id, p.proposer as proposer, p.data as data LIMIT 1";
            Result res = session.run(cypher, Map.of("me", myUser));
            return res.hasNext() ? res.next() : null;
        }
    }

    public void castVote(String proposalId, VoteType vote) {
        String user = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            // MERGE User và tạo quan hệ VOTED
            session.run("MATCH (p:ModelProposal {id: $pid}) " +
                            "MERGE (u:SessionUser {name: $user}) " +
                            "ON CREATE SET u.status = 'ONLINE', u.lastActive = timestamp() " +
                            "MERGE (u)-[v:VOTED]->(p) SET v.type = $vtype",
                    Map.of("pid", proposalId, "user", user, "vtype", vote.name()));
        }
    }

    public String getMyProposalStatus() {
        String myUser = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            String cypher = "MATCH (p:ModelProposal {proposer: $me}) " +
                    "WHERE p.status IN ['PENDING', 'APPROVED'] " +
                    "RETURN p.status AS status ORDER BY p.timestamp DESC LIMIT 1";
            var res = session.run(cypher, java.util.Map.of("me", myUser));
            return res.hasNext() ? res.next().get("status").asString() : "NONE";
        } catch (Exception e) {
            return "NONE";
        }
    }
    /**
     * Gửi đề xuất thay đổi Model lên Database Neo4j để bắt đầu quy trình bỏ phiếu.
     * @param diffReport Nội dung báo cáo sự khác biệt (sinh từ ModelDiff.generateForwardReport)
     */
    public void submitModelProposal(String diffReport) {
        // 1. Lấy thông tin người đề xuất (User [Key] (PCName))
        String user = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();

        // 2. Mở session tới Database hiện hành
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {

            // 3. Câu lệnh Cypher tạo Node ModelProposal
            // status: 'PENDING' - Trạng thái đang chờ phê duyệt
            // data: Lưu chuỗi thống kê thay đổi để những người khác đọc và quyết định
            String cypher = "CREATE (p:ModelProposal { " +
                    "  id: randomUUID(), " +
                    "  proposer: $user, " +
                    "  data: $data, " +
                    "  status: 'PENDING', " +
                    "  timestamp: timestamp() " +
                    "}) " +
                    "WITH p " +
                    "MATCH (u:SessionUser {name: $user}) " +
                    "MERGE (u)-[:PROPOSED]->(p)"; // Nối quan hệ để biết ai là chủ nhân

            session.run(cypher, Map.of(
                    "user", user,
                    "data", diffReport
            ));

            System.out.println("Consensus Service: Proposal submitted by " + user);
        } catch (Exception e) {
            System.err.println("Failed to submit model proposal: " + e.getMessage());
            throw new RuntimeException("Could not send proposal to Neo4j. Check connection.");
        }
    }


    public boolean checkMyProposalApproval() {
        String myUser = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            // Chỉ đếm những người dùng có tương tác trong vòng 1 phút qua (Online thực sự)
            String cypher =
                    "MATCH (p:ModelProposal {proposer: $me, status: 'PENDING'}) " +
                            "OPTIONAL MATCH (other:SessionUser)-[v:VOTED]->(p) WHERE v.type = 'APPROVE' " +
                            "WITH p, count(DISTINCT other) as approveCount " +
                            "MATCH (total:SessionUser) WHERE total.name <> $me AND total.lastActive > (timestamp() - 60000) " +
                            "RETURN approveCount >= count(total) AND count(total) > 0 as approved";

            Result res = session.run(cypher, Map.of("me", myUser));
            return res.hasNext() && res.single().get("approved").asBoolean();
        } catch (Exception e) { return false; }
    }

    public void finalizeProposal() {
        String myUser = Neo4jDriverManager.getInstance().getSessionManager().getUserDisplayName();
        try (Session session = Neo4jDriverManager.getInstance().openSession()) {
            session.run("MATCH (p:ModelProposal {proposer: $me, status: 'PENDING'}) SET p.status = 'APPROVED'", Map.of("me", myUser));
        }
    }


}