public class BasicStrategy {

	private enum Doubling { NO, ANY_2, ON_9_10_11, ON_10_11 }
	private enum Choice { STAND, HIT, DOUBLE, SPLIT }

	private boolean DEALER_STANDS_ON_SOFT_17 = true;
	private Doubling DOUBLING = Doubling.ANY_2;
	private boolean DOUBLE_AFTER_SPLIT_ALLOWED = true;
	private boolean RESPLIT = false; // Not supported
	private int TABLE_WINS_ON_EQUAL_UP_TO = 16;

	private double[] memoDealer;
	private boolean[] calcedDealer, calcedHand;
	private Result[] memoHand;

	private static class Result {
		public double expectedOutcome;
		public Choice action;

		private Result(double expectedOutcome, Choice action) {
			this.expectedOutcome = expectedOutcome;
			this.action = action;
		}
	}

	private String getCode(Result result) {
		switch (result.action) {
			case DOUBLE: return "D";
			case SPLIT: return "P";
			case STAND: return "S";
			case HIT : return "H";
		}
		throw new RuntimeException();
	}

	private double evaluate(int dealer, int hand) {
		assert dealer >= 17 && dealer <= 21 && hand <= 21; // And hand isn't BJ
		// Dealer beat you
		if (dealer > hand) return -1;
		// You beat dealer
		if (hand > dealer) return 1;
		// Equal, but check if dealer wins on equal
		if (hand <= TABLE_WINS_ON_EQUAL_UP_TO) return -1;
		return 0;
	}

	private double calculateDealer(int dealer, boolean dealerSoft, int hand) {
		assert hand <= 21;

		if (dealer > 21) {
			if (dealerSoft) {
				return calculateDealer(dealer - 10, false, hand);
			}
			// Dealer busted
			return 1;
		}

		if (dealer > 17 || (dealer == 17 && (!dealerSoft || DEALER_STANDS_ON_SOFT_17))) {
			// Dealer stands
			return evaluate(dealer, hand);
		}

		int state = (dealer * 2 + (dealerSoft ? 1 : 0)) * 22 + hand;
		if (calcedDealer[state]) return memoDealer[state];

		double sum = 0.0;
		for (int card = 1; card <= 13; card++) {
		    if (card == 1) {
				if (dealerSoft) {
					// Already has an ace, special care needed
					sum += calculateDealer(dealer + 1, true, hand);
				} else {
					sum += calculateDealer(dealer + 11, true, hand);
				}
			} else {
				int cardValue = card > 10 ? 10 : card;
				sum += calculateDealer(dealer + cardValue, dealerSoft, hand);
			}
		}
		sum /= 13;

		calcedDealer[state] = true;
		memoDealer[state] = sum;
		
		return sum;
	}

	private boolean isDoubleAllowed(int hand) {
		switch (DOUBLING) {
			case NO:
				return false;
			case ANY_2:
				return true;
			case ON_9_10_11:
				return hand == 9 || hand == 10 || hand == 11;
			case ON_10_11:
				return hand == 10 || hand == 11;
		}
		throw new RuntimeException();
	}

	private Result calculateHand(
			int dealer,
			boolean dealerSoft,
			int hand,
			boolean handSoft,
			boolean doubleAllowed,
			boolean splitAllowed) {

		if (hand > 21) {
			if (handSoft) {
				return calculateHand(dealer, dealerSoft, hand - 10, false, doubleAllowed, splitAllowed);
			}
			return new Result(-1.0, Choice.STAND);
		}

		int state = ((((dealer * 2 + (dealerSoft ? 1 : 0)) * 22 + hand) * 2 + (handSoft ? 1 : 0)) * 2 + (doubleAllowed ? 1 : 0)) * 2 + (splitAllowed ? 1 : 0);
		if (calcedHand[state]) return memoHand[state];

		Choice bestChoice = Choice.STAND;
		double best = calculateDealer(dealer, dealerSoft, hand);

		// Try double, if allowed
		if (doubleAllowed && isDoubleAllowed(hand)) {
			double doubleSum = 0.0;
			for (int card = 1; card <= 13; card++) {
				int aces = (handSoft ? 1 : 0) + (card == 1 ? 1 : 0);
				int cardValue = card == 1 ? 11 : (card > 10 ? 10 : card);
				int result = hand + cardValue;

				while (result > 21 && aces > 0) {
					result -= 10;
					aces--;
				}

				if (result > 21) {
					doubleSum -= 2.0;
				} else {
					doubleSum += 2 * calculateDealer(dealer, dealerSoft, result);
				}
			}
			if (doubleSum > best) {
				best = doubleSum;
				bestChoice = Choice.DOUBLE;
			}
		}

		// Try split, if allowed
		if (splitAllowed) {
			int splitCard = handSoft ? 11 : (hand / 2); // 11 if we split aces
			double splitSum = 0.0;
			for (int card = 1; card <= 13; card++) {
				if (card == 1) {
					if (splitCard == 11) {
						// We split aces and got a new ace
						splitSum += calculateHand(dealer, dealerSoft, 12, true, DOUBLE_AFTER_SPLIT_ALLOWED, RESPLIT).expectedOutcome;
					} else {
						// We split a non-ace and got an ace
						splitSum += calculateHand(dealer, dealerSoft, splitCard + 11, true, DOUBLE_AFTER_SPLIT_ALLOWED, false).expectedOutcome;
					}
				} else {
					// We split some cards (possibly aces) and got a non-ace
					int cardValue = card > 10 ? 10 : card;
					splitSum += calculateHand(dealer, dealerSoft, splitCard + cardValue, handSoft, DOUBLE_AFTER_SPLIT_ALLOWED, RESPLIT && (cardValue == splitCard)).expectedOutcome;
				}
			}
			splitSum /= 13;
			if (splitSum > best) {
				best = splitSum;
				bestChoice = Choice.SPLIT;
			}
		}

		// Try hit
		double hitSum = 0.0;
		for (int card = 1; card <= 13; card++) {
			int aces = (handSoft ? 1 : 0) + (card == 1 ? 1 : 0);
			int cardValue = card == 1 ? 11 : (card > 10 ? 10 : card);
			int result = hand + cardValue;
			if (aces == 2) {
				assert result > 21;
				aces--;
				result -= 10;
			}

			hitSum += calculateHand(dealer, dealerSoft, result, aces == 1, false, false).expectedOutcome;
		}
		hitSum /= 13;
		if (hitSum > best) {
			best = hitSum;
			bestChoice = Choice.HIT;
		}

		calcedHand[state] = true;
		memoHand[state] = new Result(best, bestChoice);

		return memoHand[state];
	}

	private void init() {
		calcedDealer = new boolean[22*2*22];
		memoDealer = new double[22*2*22];
		calcedHand = new boolean[22*2*22*8];
		memoHand = new Result[22*2*22*8];
	}

	private void showSheet() {
		init();

		System.out.print("    ");
		for (int dealer = 2; dealer <= 10; dealer++) {
			System.out.print(String.format("%3d", dealer));
		}
		System.out.println("  A");
		System.out.println("--------------------------------------------");
		for (int hand = 5; hand <= 17 ; hand++) {
			System.out.print(String.format("%3d: ", hand));
			for (int dealer = 2; dealer <= 11; dealer++) {
			    Result res = calculateHand(dealer, dealer == 11, hand, false, true, false);
				System.out.print(" " + getCode(res) + " ");
			}
			System.out.println();
		}

		System.out.println();

		for (int hand = 2; hand <= 9 ; hand++) {
			System.out.print(String.format("A,%d: ", hand));
			for (int dealer = 2; dealer <= 11; dealer++) {
			    Result res = calculateHand(dealer, dealer == 11, hand + 11, true, true, false);
				System.out.print(" " + getCode(res) + " ");
			}
			System.out.println();
		}

		System.out.println();

		for (int hand = 2; hand <= 11; hand++) {
			if (hand < 10) {
				System.out.print(String.format("%d,%d: ", hand, hand));
			} else if (hand == 10) {
				System.out.print("T,T: ");
			} else if (hand == 11) {
				System.out.print("A,A: ");
			}
			
			for (int dealer = 2; dealer <= 11; dealer++) {
				int handSum = hand == 11 ? 12 : (hand * 2);
			    Result res = calculateHand(dealer, dealer == 11, handSum, dealer == 11, true, true);
				System.out.print(" " + getCode(res) + " ");
			}
			System.out.println();
		}
	}


	public static void main(String[] args) {
		BasicStrategy basicStrategy = new BasicStrategy();
		basicStrategy.showSheet();
	}
}
