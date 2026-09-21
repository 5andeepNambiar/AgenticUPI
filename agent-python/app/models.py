from typing import Optional, Literal
from pydantic import BaseModel, ConfigDict, Field, constr

class PaymentIntent(BaseModel):
    # Enforce strict parsing without silent coercion (e.g., rejecting float to string)
    model_config = ConfigDict(strict=True)

    # In production, this should be injected by the authenticated User Session, NOT the LLM!
    # Forcing regex constraint to prevent arbitrary injection
    payer_vpa: constr(pattern=r'^[a-zA-Z0-9.\-_]+@[a-zA-Z]+$') = Field(
        ..., description="The UPI VPA of the user initiating the payment."
    )

    payee_vpa: constr(pattern=r'^[a-zA-Z0-9.\-_]+@[a-zA-Z]+$') = Field(
        ..., description="The VPA of the merchant or payee."
    )

    amount_paise: int = Field(
        ..., gt=0, le=50000, description="Amount in paise. Must be > 0 and <= 50000 (₹500)."
    )

    currency: Literal["INR"] = Field(
        default="INR", description="ISO 4217 currency code. Only INR supported."
    )

    category: Literal["FOOD", "TRANSPORT", "BILLS", "SHOPPING"] = Field(
        ..., description="Merchant category string."
    )

    agent_context: Optional[str] = Field(
        None, max_length=500, description="Untrusted contextual justification for this payment (e.g. 'Paid for Uber')."
    )
