public class BasicStrategy {

	private enum Doubling { NO, ANY_2, ON_9_10_11, ON_10_11 }
	private enum Choice { STAND, HIT, DOUBLE, SPLIT }

	private boolean DEALER_STANDS_ON_SOFT_17 = true;
	private Doubling DOUBLING = Doubling.ANY_2;
	private boolean DOUBLE_AFTER_SPLIT_ALLOWED = true;
	private int TABLE_WINS_ON_EQUAL_UP_TO = 16;
	private int MAX_SPLITS = 4;

	private double[] memoDealer;
	private boolean[] calcedDealer, calcedHand;
	private Result[] memoHand;
	private double[][][] expStand, expDouble, expSplit, expHit;

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

	private String getCode(Result resAllowDouble, Result resNoDouble) {
		String r1 = getCode(resAllowDouble);
		String r2 = getCode(resNoDouble);
		if (r1.equals(r2)) return r1 + " ";
		String s = r1 + r2;
		if (s.equals("DH")) {
			s = "D ";
		}
		return s;
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

	private double calculateDealer(int dealer, boolean dealerSoft, boolean secondDealerCard, int hand) {
		assert hand <= 21;

		if (dealer > 21) {
			if (dealerSoft) {
				return calculateDealer(dealer - 10, false, false, hand);
			}
			// Dealer busted
			return 1;
		}

		if (dealer > 17 || (dealer == 17 && (!dealerSoft || DEALER_STANDS_ON_SOFT_17))) {
			// Dealer stands
			return evaluate(dealer, hand);
		}

		int state = ((dealer * 2 + (dealerSoft ? 1 : 0)) * 22 + hand) * 2 + (secondDealerCard ? 1 : 0);
		if (calcedDealer[state]) return memoDealer[state];

		double sum = 0.0;
		for (int card = 1; card <= 13; card++) {
		    if (card == 1) {
				if (dealerSoft) {
					// Already has an ace, special care needed
					sum += calculateDealer(dealer + 1, true, false, hand);
				} else {
					if (dealer == 10 && secondDealerCard) {
						// Dealer got BJ
						sum -= 1.0;
					} else {
						sum += calculateDealer(dealer + 11, true, false, hand);
					}
				}
			} else {
				int cardValue = card > 10 ? 10 : card;
				if (dealer + cardValue == 21 && secondDealerCard) {
					// Dealer got BJ
					sum -= 1.0;
				} else {
					sum += calculateDealer(dealer + cardValue, dealerSoft, false, hand);
				}
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
			int hand,
			boolean handSoft,
			boolean doubleAllowed,
			int splitsLeft) {

		if (hand > 21) {
			if (handSoft) {
				return calculateHand(dealer, hand - 10, false, doubleAllowed, splitsLeft);
			}
			return new Result(-1.0, Choice.STAND);
		}

		int state = (((dealer * 22 + hand) * 2 +
				(handSoft ? 1 : 0)) * 2 + (doubleAllowed ? 1 : 0)) * (MAX_SPLITS + 1) + splitsLeft;
		if (calcedHand[state]) return memoHand[state];

		Choice bestChoice = Choice.STAND;
		double best = calculateDealer(dealer, dealer == 11, true, hand);

		expStand[dealer][hand][handSoft ? 1 : 0] = best;

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
					doubleSum += 2 * calculateDealer(dealer, dealer == 11, true, result);
				}
			}
			doubleSum /= 13.0;
			if (doubleSum > best) {
				best = doubleSum;
				bestChoice = Choice.DOUBLE;
			}
			expDouble[dealer][hand][handSoft ? 1 : 0] = doubleSum;
		}

		// Try split, if allowed
		if (splitsLeft > 0) {
			int splitCard = handSoft ? 11 : (hand / 2); // 11 if we split aces
			double splitSum = 0.0;
			for (int card = 1; card <= 13; card++) {
				if (card == 1) {
					if (splitCard == 11) {
						// We split aces and got a new ace
						splitSum += calculateHand(dealer, 12, true, DOUBLE_AFTER_SPLIT_ALLOWED, splitsLeft - 1).expectedOutcome;
					} else {
						// We split a non-ace and got an ace
						splitSum += calculateHand(dealer, splitCard + 11, true, DOUBLE_AFTER_SPLIT_ALLOWED, 0).expectedOutcome;
					}
				} else {
					// We split some cards (possibly aces) and got a non-ace
					int cardValue = card > 10 ? 10 : card;
					splitSum += calculateHand(dealer, splitCard + cardValue, handSoft, DOUBLE_AFTER_SPLIT_ALLOWED, cardValue == splitCard ? splitsLeft - 1 : 0).expectedOutcome;
				}
			}
			splitSum = splitSum * 2 / 13;
			if (splitSum > best) {
				best = splitSum;
				bestChoice = Choice.SPLIT;
			}
			if (splitsLeft == MAX_SPLITS) {
				expSplit[dealer][hand][handSoft ? 1 : 0] = splitSum;
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

			hitSum += calculateHand(dealer, result, aces == 1, false, 0).expectedOutcome;
		}
		hitSum /= 13;
		if (hitSum > best) {
			best = hitSum;
			bestChoice = Choice.HIT;
		}
		expHit[dealer][hand][handSoft ? 1 : 0] = hitSum;

		calcedHand[state] = true;
		memoHand[state] = new Result(best, bestChoice);

		return memoHand[state];
	}

	private void init() {
		calcedDealer = new boolean[22*2*22*2];
		memoDealer = new double[22*2*22*2];
		calcedHand = new boolean[22*22*4*(MAX_SPLITS + 1)];
		memoHand = new Result[22*22*4*(MAX_SPLITS + 1)];
		expDouble = new double[12][22][2];
		expHit = new double[12][22][2];
		expSplit = new double[12][22][2];
		expStand = new double[12][22][2];
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
			    Result res1 = calculateHand(dealer, hand, false, true, 0);
			    Result res2 = calculateHand(dealer, hand, false, false, 0);
				System.out.print(" " + getCode(res1, res2));
			}
			System.out.println();
		}

		System.out.println();

		for (int hand = 2; hand <= 9 ; hand++) {
			System.out.print(String.format("A,%d: ", hand));
			for (int dealer = 2; dealer <= 11; dealer++) {
			    Result res1 = calculateHand(dealer, hand + 11, true, true, 0);
			    Result res2 = calculateHand(dealer, hand + 11, true, false, 0);
				System.out.print(" " + getCode(res1, res2));
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
			    Result res1 = calculateHand(dealer, handSum, hand == 11, true, MAX_SPLITS);
			    Result res2 = calculateHand(dealer, handSum, hand == 11, false, MAX_SPLITS);
				System.out.print(" " + getCode(res1, res2));
			}
			System.out.println();
		}
		System.out.println();
/*
		System.out.println("8,8:");
		for (int dealer = 2; dealer <= 11; dealer++) {
			System.out.println("Dealer: " + dealer);
			System.out.println("  Hit...: " + expHit[dealer][16][0]);
			System.out.println("  Stand.: " + expStand[dealer][16][0]);
			System.out.println("  Double: " + expDouble[dealer][16][0]);
			System.out.println("  Split.: " + expSplit[dealer][16][0]);
			System.out.println();
		}*/

	}


	private void calculateExpectedOutcome() {
		init();
		
		double sum = 0.0;
		for (int card1 = 1; card1 <= 13; card1++) {
			int card1Value = card1 == 1 ? 11 : (card1 > 10 ? 10 : card1);
		    for (int card2 = 1; card2 <= 13; card2++) {
		    	int card2Value = card2 == 1 ? 11 : (card2 > 10 ? 10 : card2);
				for (int dealer1 = 1; dealer1 <= 13; dealer1++) {
				    int dealer1Value = dealer1 == 1 ? 11 : (dealer1 > 10 ? 10 : dealer1);

					int handSum = card1Value + card2Value;
					if (handSum == 22) {
						handSum = 12;
					}

					if (handSum == 21) {
						// Black Jack!
						for (int dealer2 = 1; dealer2 <= 13; dealer2++) {
							int dealer2Value = dealer2 == 1 ? 11 : (dealer2 > 10 ? 10 : dealer2);
							if (dealer1Value + dealer2Value == 21) {
								// Dealer also got a BJ - a push
								sum += 0.0;
							} else {
								sum += 1.5 / (13*13*13*13);
							}
						}
					} else {
						Result res = calculateHand(dealer1Value, handSum, card1 == 1 || card2 == 1, true, card1 == card2 ? MAX_SPLITS : 0);
						sum += res.expectedOutcome / (13*13*13);
					}
				}
		    }
		}

		System.out.println("Expected outcome: " + sum);
	}

	public static void main(String[] args) {
		BasicStrategy basicStrategy = new BasicStrategy();
		basicStrategy.showSheet();
		basicStrategy.calculateExpectedOutcome();
	}
}
