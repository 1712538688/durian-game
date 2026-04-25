package com.durian.game;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;
import java.net.InetSocketAddress;
import java.util.*;

public class GameServer extends WebSocketServer {

    private final List<Player> players = new ArrayList<>();
    private final List<Card> orderDeck = new ArrayList<>();
    private final List<String> orderCards = new ArrayList<>();
    private final Map<String, Integer> orderSummary = new HashMap<>();
    private int currentTurnIndex = 0;
    private String phase = "waiting";
    private final int[] angerPool = {1,2,3,4,5,6,7};
    private int nextAngerIdx = 0;
    private Map<String, StockCard> revealedStocks = null;

    private static final String[] FRUITS = {"durian","banana","apple","orange","grape"};

    static class Player {
        String id, name;
        WebSocket conn;
        StockCard stockCard;
        List<Integer> angerTokens = new ArrayList<>();
    }
    static class StockCard {
        String id;
        FruitCount top, bottom;
        boolean isSister;
    }
    static class FruitCount {
        String fruit;
        int count;
    }
    static class Card {
        String type;
        String[] options;
        String gorillaType, gorillaName;
    }

    public GameServer(InetSocketAddress address) {
        super(address);
    }

    @Override public void onStart() {}
    @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {}
    @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Player p = findPlayer(conn);
        if (p != null) {
            players.remove(p);
            broadcast("player_left", "{\"message\":\"" + p.name + " 离开了\"}");
            if (players.size() < 2 && phase.equals("playing")) phase = "waiting";
        }
    }
    @Override public void onError(WebSocket conn, Exception ex) {}

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.getString("type");
            Player player = findPlayer(conn);

            switch (type) {
                case "join": {
                    if (!phase.equals("waiting")) {
                        conn.send("{\"type\":\"error\",\"message\":\"游戏已开始\"}");
                        return;
                    }
                    if (players.size() >= 7) {
                        conn.send("{\"type\":\"error\",\"message\":\"房间已满\"}");
                        return;
                    }
                    String name = json.getString("playerName");
                    String id = UUID.randomUUID().toString().substring(0,8);
                    Player newPlayer = new Player();
                    newPlayer.id = id; newPlayer.name = name; newPlayer.conn = conn;
                    players.add(newPlayer);
                    conn.send("{\"type\":\"joined\",\"playerId\":\""+id+"\",\"playerCount\":"+players.size()+",\"maxPlayers\":7}");
                    broadcast("waiting_for_players", "{\"count\":"+players.size()+",\"max\":7}");
                    if (players.size() >= 2) {
                        new Thread(() -> {
                            try { Thread.sleep(2000); } catch (Exception e) {}
                            if (players.size() >= 2 && phase.equals("waiting")) {
                                resetGame();
                                broadcast("game_start", stateJSON());
                                sendTurnNotification();
                            }
                        }).start();
                    }
                    break;
                }
                case "flip_card": {
                    if (!phase.equals("playing") || player==null || !players.get(currentTurnIndex).id.equals(player.id)) return;
                    if (orderDeck.isEmpty()) {
                        conn.send("{\"type\":\"error\",\"message\":\"牌库已空，必须摇铃质疑\"}");
                        return;
                    }
                    Card card = orderDeck.remove(orderDeck.size()-1);
                    if (card.type.equals("order")) {
                        String opts = "[\""+card.options[0]+"\",\""+card.options[1]+"\"]";
                        conn.send("{\"type\":\"card_flipped\",\"card\":{\"options\":"+opts+"}}");
                    } else {
                        String detail = "";
                        if (card.gorillaType.equals("brother")) {
                            int n = Math.min(3, orderCards.size());
                            if (n>0) orderCards.subList(orderCards.size()-n, orderCards.size()).clear();
                            detail = "移除了最近 "+n+" 张订单";
                        } else if (card.gorillaType.equals("sister")) {
                            int before = orderCards.size();
                            orderCards.removeIf(f->f.equals("banana"));
                            detail = "移除了 "+(before-orderCards.size())+" 张香蕉订单";
                        } else {
                            detail = "无事发生~";
                        }
                        updateOrderSummary();
                        broadcast("gorilla_card", "{\"gorillaName\":\""+card.gorillaName+"\",\"gorillaType\":\""+card.gorillaType+"\",\"description\":\""+(card.gorillaType.equals("brother")?"移除最近3张订单":(card.gorillaType.equals("sister")?"移除所有香蕉订单":"弟弟路过"))+"\",\"effectDetail\":\""+detail+"\"}");
                        nextTurn();
                    }
                    break;
                }
                case "select_fruit": {
                    if (!phase.equals("playing") || player==null || !players.get(currentTurnIndex).id.equals(player.id)) return;
                    String fruit = json.getString("fruit");
                    if (!Arrays.asList(FRUITS).contains(fruit)) return;
                    orderCards.add(fruit);
                    updateOrderSummary();
                    nextTurn();
                    break;
                }
                case "ring_bell": {
                    if (!phase.equals("playing") || player==null || !players.get(currentTurnIndex).id.equals(player.id)) return;
                    if (orderCards.isEmpty()) {
                        conn.send("{\"type\":\"error\",\"message\":\"订单区为空\"}");
                        return;
                    }
                    String fruit = orderSummary.keySet().iterator().next();
                    phase = "arbitration";
                    executeArbitration(player, fruit);
                    break;
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void resetGame() {
        List<StockCard> stocks = new ArrayList<>();
        stocks.add(sisterCard());
        String[][] combos = {{"durian","banana"},{"durian","apple"},{"banana","orange"},{"banana","grape"},{"apple","durian"},{"apple","grape"},{"orange","durian"},{"orange","apple"},{"grape","banana"},{"grape","orange"},{"durian","durian"},{"banana","banana"},{"apple","apple"},{"orange","orange"},{"grape","grape"}};
        for (int i=0;i<players.size()-1 && i<combos.length;i++) {
            StockCard c = new StockCard();
            c.id = "stock_"+i; c.isSister=false;
            c.top = new FruitCount(); c.top.fruit=combos[i][0]; c.top.count=new Random().nextInt(3)+1;
            c.bottom = new FruitCount(); c.bottom.fruit=combos[i][1]; c.bottom.count=new Random().nextInt(3)+1;
            stocks.add(c);
        }
        Collections.shuffle(stocks);
        for (int i=0;i<players.size();i++) {
            players.get(i).stockCard = stocks.get(i);
            players.get(i).angerTokens.clear();
        }
        orderDeck.clear();
        for (int i=0;i<30;i++) {
            String f1 = FRUITS[new Random().nextInt(FRUITS.length)];
            String f2 = FRUITS[new Random().nextInt(FRUITS.length)];
            while (f2.equals(f1)) f2 = FRUITS[new Random().nextInt(FRUITS.length)];
            Card c = new Card(); c.type="order"; c.options=new String[]{f1,f2}; orderDeck.add(c);
        }
        Card g1 = new Card(); g1.type="gorilla"; g1.gorillaType="brother"; g1.gorillaName="哥哥"; orderDeck.add(g1);
        Card g2 = new Card(); g2.type="gorilla"; g2.gorillaType="sister"; g2.gorillaName="妹妹"; orderDeck.add(g2);
        Card g3 = new Card(); g3.type="gorilla"; g3.gorillaType="brother"; g3.gorillaName="哥哥"; orderDeck.add(g3);
        Card g4 = new Card(); g4.type="gorilla"; g4.gorillaType="younger_brother"; g4.gorillaName="弟弟"; orderDeck.add(g4);
        Collections.shuffle(orderDeck);
        orderCards.clear(); orderSummary.clear();
        currentTurnIndex=0; phase="playing"; nextAngerIdx=0; revealedStocks=null;
    }

    private StockCard sisterCard() {
        StockCard c = new StockCard();
        c.id="sister_stock"; c.isSister=true;
        c.top = new FruitCount(); c.top.fruit="banana"; c.top.count=Integer.MAX_VALUE;
        return c;
    }

    private void updateOrderSummary() {
        orderSummary.clear();
        for (String f : orderCards) orderSummary.put(f, orderSummary.getOrDefault(f,0)+1);
    }

    private void nextTurn() {
        currentTurnIndex = (currentTurnIndex+1) % players.size();
        broadcast("game_state", stateJSON());
        sendTurnNotification();
    }

    private int calcStockTotal(String fruit) {
        int total=0;
        for (Player p : players) {
            StockCard s = revealedStocks!=null ? revealedStocks.get(p.id) : p.stockCard;
            if (s==null) continue;
            if (s.isSister && fruit.equals("banana")) return Integer.MAX_VALUE;
            if (s.top!=null && s.top.fruit.equals(fruit)) total+=s.top.count;
            if (s.bottom!=null && s.bottom.fruit.equals(fruit)) total+=s.bottom.count;
        }
        return total;
    }

    private void executeArbitration(Player challenger, String fruit) {
        int orderTotal = orderSummary.getOrDefault(fruit,0);
        int stockTotal = calcStockTotal(fruit);
        revealedStocks = new HashMap<>();
        for (Player p : players) revealedStocks.put(p.id, p.stockCard);
        boolean correct = orderTotal > stockTotal;
        int prevIdx = currentTurnIndex-1; if (prevIdx<0) prevIdx=players.size()-1;
        Player punished = correct ? players.get(prevIdx) : challenger;
        int token = angerPool[nextAngerIdx++];
        punished.angerTokens.add(token);
        if (punished.angerTokens.size()>=7) phase="game_over";
        broadcast("arbitration", "{\"correct\":"+correct+",\"challengerName\":\""+challenger.name+"\",\"fruitQuestioned\":\""+fruit+"\",\"orderTotal\":"+orderTotal+",\"stockTotal\":"+(stockTotal==Integer.MAX_VALUE?"\"∞\"":stockTotal)+",\"punishedName\":\""+punished.name+"\",\"angerToken\":"+token+",\"sisterEffect\":"+(stockTotal==Integer.MAX_VALUE)+"}");
        if (phase.equals("game_over")) {
            StringBuilder scores = new StringBuilder("[");
            for (Player p : players) {
                int sum = p.angerTokens.stream().mapToInt(i->i).sum();
                scores.append("{\"name\":\"").append(p.name).append("\",\"totalAnger\":").append(sum).append(",\"angerTokens\":[").append(p.angerTokens.toString().replaceAll("[\\[\\]]","")).append("]},");
            }
            if (scores.charAt(scores.length()-1)==',') scores.deleteCharAt(scores.length()-1);
            scores.append("]");
            broadcast("game_over", scores.toString());
            return;
        }
        new Thread(() -> {
            try { Thread.sleep(3500); } catch (Exception e) {}
            orderCards.clear(); orderSummary.clear(); revealedStocks=null;
            currentTurnIndex = players.indexOf(punished);
            broadcast("game_state", stateJSON());
            sendTurnNotification();
        }).start();
    }

    private void sendTurnNotification() {
        if (phase.equals("playing") && !players.isEmpty())
            players.get(currentTurnIndex).conn.send("{\"type\":\"your_turn\"}");
    }

    private String stateJSON() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"players\":[");
        for (int i=0;i<players.size();i++) {
            Player p = players.get(i);
            sb.append("{\"id\":\"").append(p.id).append("\",\"name\":\"").append(p.name).append("\",\"angerTokens\":[");
            for (int j=0;j<p.angerTokens.size();j++) {
                if (j>0) sb.append(",");
                sb.append(p.angerTokens.get(j));
            }
            sb.append("]");
            if (revealedStocks!=null && revealedStocks.containsKey(p.id)) {
                sb.append(",\"stockCard\":").append(stockCardToJSON(revealedStocks.get(p.id)));
            } else if (p.stockCard!=null) {
                sb.append(",\"stockCard\":").append(stockCardToJSON(p.stockCard));
            }
            sb.append("}");
            if (i<players.size()-1) sb.append(",");
        }
        sb.append("],\"orderCards\":[");
        for (int i=0;i<orderCards.size();i++) {
            if (i>0) sb.append(",");
            sb.append("\"").append(orderCards.get(i)).append("\"");
        }
        sb.append("],\"orderSummary\":{");
        boolean first = true;
        for (Map.Entry<String,Integer> e : orderSummary.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("},\"deckRemaining\":").append(orderDeck.size())
          .append(",\"currentTurnPlayerId\":\"").append(players.isEmpty()?"":players.get(currentTurnIndex).id)
          .append("\",\"phase\":\"").append(phase).append("\"");
        if (revealedStocks!=null) {
            sb.append(",\"revealedStocks\":{");
            first = true;
            for (Player p : players) {
                if (!first) sb.append(",");
                sb.append("\"").append(p.id).append("\":").append(stockCardToJSON(revealedStocks.get(p.id)));
                first = false;
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private String stockCardToJSON(StockCard s) {
        if (s==null) return "null";
        return "{\"isSister\":"+s.isSister+",\"top\":"+(s.top!=null?"{\"fruit\":\""+s.top.fruit+"\",\"count\":"+(s.isSister?"\"∞\"":s.top.count)+"}":"null")+",\"bottom\":"+(s.bottom!=null?"{\"fruit\":\""+s.bottom.fruit+"\",\"count\":"+s.bottom.count+"}":"null")+"}";
    }

    private Player findPlayer(WebSocket conn) {
        return players.stream().filter(p->p.conn==conn).findFirst().orElse(null);
    }

    private void broadcast(String type, String data) {
        String msg = "{\"type\":\""+type+"\""+ (data.startsWith("[") || data.startsWith("{") ? ",\"state\":"+data : ",\"data\":"+data) + "}";
        for (Player p : players) {
            if (p.conn.isOpen()) p.conn.send(msg);
        }
    }
}
