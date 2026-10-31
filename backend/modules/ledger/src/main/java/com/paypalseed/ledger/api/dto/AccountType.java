package com.paypalseed.ledger.api.dto;

public enum AccountType {
    PROGRAM_CASH(Side.DEBIT, Owner.PROGRAM),
    PAYPAL_FEES(Side.DEBIT, Owner.PROGRAM),
    LOANS_RECEIVABLE(Side.DEBIT, Owner.LOAN),
    MEMBER_OVERPAYMENT(Side.CREDIT, Owner.MEMBER),
    MEMBER_SAVINGS(Side.CREDIT, Owner.MEMBER),
    LOSS_RESERVE(Side.CREDIT, Owner.PROGRAM),
    COVERED_LOSSES(Side.DEBIT, Owner.PROGRAM);

    public enum Owner {
        PROGRAM,
        LOAN,
        MEMBER
    }

    private final Side normalSide;
    private final Owner owner;

    AccountType(Side normalSide, Owner owner) {
        this.normalSide = normalSide;
        this.owner = owner;
    }

    /** The side that increases the account: debit for assets and expenses, credit for liabilities and funds. */
    public Side normalSide() {
        return normalSide;
    }

    public Owner owner() {
        return owner;
    }
}
