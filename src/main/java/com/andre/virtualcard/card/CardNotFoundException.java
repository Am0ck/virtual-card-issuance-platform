package com.andre.virtualcard.card;

import java.util.UUID;

public class CardNotFoundException extends RuntimeException {

    public CardNotFoundException(UUID cardId) {
        super("Card " + cardId + " was not found");
    }
}
