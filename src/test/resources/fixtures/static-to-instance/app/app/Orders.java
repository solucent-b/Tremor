package app;

public class Orders {
    public String newOrderId() {
        return "order-" + lib.Ids.next();
    }
}
