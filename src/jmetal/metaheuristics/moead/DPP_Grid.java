/**
 * DPP_Epsilon.java
 * 
 * This is the main implementation of ED/DPP-DRA.
 * 
 * Author:
 * 		Ke Li <k.li@exeter.ac.uk>
 * 
 * Reference:
 *		K. Li, K. Deb, Q. Zhang,
 *		“Evolutionary Multiobjective Optimization With Hybrid Selection Principles”,
 *		Proc. of 2015 IEEE Congress on Evolutionary Computation (CEC’15), IEEE Press: p. 900–907, May 2015. 
 * 
 * Homepage:
 * 		https://coda-group.github.io/
 * 
 * Copyright (c) 2017 Ke Li
 * 
 * Note: This is a free software developed based on the open source project 
 * jMetal<http://jmetal.sourceforge.net>. The copy right of jMetal belongs to 
 * its original authors, Antonio J. Nebro and Juan J. Durillo. Nevertheless, 
 * this current version can be redistributed and/or modified under the terms of 
 * the GNU Lesser General Public License as published by the Free Software 
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, 
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */

package jmetal.metaheuristics.moead;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;

import jmetal.core.Algorithm;
import jmetal.core.Operator;
import jmetal.core.Problem;
import jmetal.core.Solution;
import jmetal.core.SolutionSet;
import jmetal.util.Distance;
import jmetal.util.JMException;
import jmetal.util.PseudoRandom;

public class DPP_Grid extends Algorithm {
	int gen = 0;
	
	private int populationSize_;
	
	private SolutionSet population_;	// diversity archive
	private SolutionSet archive_pop;	// convergence archive
	private SolutionSet mixed_pop;
	private SolutionSet final_pop;      // output population
	private Solution[] savedValues_;	// Stores the values of the individuals

	private double[] utility_;
	private int[] frequency_;

	double[] z_;				// ideal objective vector
	double[] nz_;				// nadir objective vector
	double[][] lambda_;			// weight vector
	
	int T_;						// neighborhood size
	int nr_;				    // maximal number of solutions replaced by each child solution
	int evaluations_;			// counter of evaluation times
	double delta_;				// probability that parent solutions are selected from neighborhood 
	int[][] neighborhood_;		// neighborhood structure
	
	Solution[] indArray_;
	String functionType_;
	
	Operator crossover_;
	Operator mutation_;

	String dataDirectory_;
	
	double[] lb;		// lower boundary
	double[] ub;		// upper boundary
	double divide;
	
	LinkedList<SolutionSet> epsilon_neighbour = new LinkedList<SolutionSet>();
	
	/** 
	 * Constructor
	 * 
	 * @param problem: Problem to solve
	 */
	public DPP_Grid(Problem problem) {
		super(problem);

//		functionType_ = "_TCHE1";
		functionType_ = "_TCHE2";
//		functionType_ = "_PBI";

	} // DMOEA

	public SolutionSet execute() throws JMException, ClassNotFoundException {
		int maxEvaluations;

		evaluations_ = 0;
		
		dataDirectory_  = this.getInputParameter("dataDirectory").toString();
		maxEvaluations  = ((Integer) this.getInputParameter("maxEvaluations")).intValue();
		populationSize_ = ((Integer) this.getInputParameter("populationSize")).intValue();
		

		population_  = new SolutionSet(populationSize_);
		archive_pop  = new SolutionSet(10 * populationSize_);
		savedValues_ = new Solution[populationSize_];
		utility_ 	 = new double[populationSize_];
		frequency_ 	 = new int[populationSize_];
		for (int i = 0; i < utility_.length; i++) {
			utility_[i] = 1.0;
			frequency_[i] = 0;
		}
		indArray_ = new Solution[problem_.getNumberOfObjectives()];

		T_     = 20;
	    nr_    = 2;
	    delta_ = 0.9;
	    divide = (int) (0.3 * populationSize_);

		neighborhood_ = new int[populationSize_][T_];

		z_ 		= new double[problem_.getNumberOfObjectives()];
		nz_     = new double[problem_.getNumberOfObjectives()];
		lambda_ = new double[populationSize_][problem_.getNumberOfObjectives()];

		crossover_ = operators_.get("crossover");
		mutation_  = operators_.get("mutation");
		
		lb = new double[problem_.getNumberOfObjectives()];
		ub = new double[problem_.getNumberOfObjectives()];
		
		for (int i = 0; i < populationSize_; i++)
			epsilon_neighbour.add(new SolutionSet(populationSize_));
		
		// STEP 1. Initialization
		initUniformWeight();
		initNeighborhood();
		initPopulation();
		initIdealPoint();
		initNadirPoint();
		updateBoundary();

		// STEP 2. Update
		do {
			int[] permutation = new int[populationSize_];
			Utils.randomPermutation(permutation, populationSize_);

			List<Integer> order = tour_selection(10);

			for (int i = 0; i < order.size(); i++) {
				int n = order.get(i);
				frequency_[n]++;

				int type;
				double rnd = PseudoRandom.randDouble();
				

				// STEP 2.1. Mating selection based on probability
				if (rnd < delta_) // if (rnd < realb)
					type = 1; // neighborhood
				else
					type = 2; // whole population
				Vector<Integer> p = new Vector<Integer>();
				matingSelection(p, n, 2, type);

				// STEP 2.2. Reproduction
				Solution child;
				Solution[] parents = new Solution[3];

				parents[0] = population_.get(p.get(0));
				parents[1] = population_.get(p.get(1));
				parents[2] = population_.get(n);

				SolutionSet kSet = epsilon_neighbour.get(n);
				if (kSet.size() != 0)
					parents[2] = kSet.get(0);

				SolutionSet mSet = epsilon_neighbour.get(p.get(1));
				if (mSet.size() != 0)
					parents[1] = mSet.get(0);

				// Apply DE crossover
				child = (Solution) crossover_.execute(new Object[] {population_.get(n), parents });

				// Apply mutation
				mutation_.execute(child);

				// Evaluation
				problem_.evaluate(child);

				evaluations_++;

				// STEP 2.3. Repair. Not necessary

				// STEP 2.4. Update z_
				updateReference(child);
				updateNadirPoint(child);
				updateBoundary();

				// STEP 2.5. Update of solutions
				updateProblem(child, n, type);
				updateArchive(child, archive_pop);
			} // for

			gen++;
			if (gen % 30 == 0)
				comp_utility();
		} while (evaluations_ < maxEvaluations);
		
		final_pop = filtering(populationSize_);
		
		return final_pop;
	}
	
	/**
	 * Initialize population
	 * @throws JMException
	 * @throws ClassNotFoundException
	 */
	public void initPopulation() throws JMException, ClassNotFoundException {
		for (int i = 0; i < populationSize_; i++) {
			Solution newSolution = new Solution(problem_);

			problem_.evaluate(newSolution);
			evaluations_++;
			population_.add(newSolution);
			savedValues_[i] = new Solution(newSolution);

			newSolution.Set_location(i);
			archive_pop.add(newSolution);
			Add_Child(newSolution, epsilon_neighbour);
		} // for
	} // initPopulation

	/**
	 * Initialize ideal objective vector
	 * @throws JMException
	 * @throws ClassNotFoundException
	 */
	void initIdealPoint() throws JMException, ClassNotFoundException {
		for (int i = 0; i < problem_.getNumberOfObjectives(); i++) {
			z_[i] = 1.0e+30;
			indArray_[i] = new Solution(problem_);
			problem_.evaluate(indArray_[i]);
			evaluations_++;
		} // for

		for (int i = 0; i < populationSize_; i++) {
			updateReference(population_.get(i));
		} // for
	} // initIdealPoint
	
	/**
	 * Update the ideal point
	 * @param individual
	 */
	void updateReference(Solution individual) {
		for (int n = 0; n < problem_.getNumberOfObjectives(); n++) {
			if (individual.getObjective(n) < z_[n]) {
				z_[n] = individual.getObjective(n);
				indArray_[n] = individual;
			}
		}
	} // updateReference
	
	/**
	 * Initialize the nadir point
	 * 
	 * @throws JMException
	 * @throws ClassNotFoundException
	 */
	void initNadirPoint() throws JMException, ClassNotFoundException {
		for (int i = 0; i < problem_.getNumberOfObjectives(); i++)
			nz_[i] = -1.0e+30;

		for (int i = 0; i < populationSize_; i++)
			updateNadirPoint(population_.get(i));
		
	} // initNadirPoint
	
	/**
  	 * Update the nadir point
  	 * 
  	 * @param individual
  	 */
	void updateNadirPoint(Solution individual) {
		for (int i = 0; i < problem_.getNumberOfObjectives(); i++) {
			if (individual.getObjective(i) > nz_[i])
				nz_[i] = individual.getObjective(i);
		}
	} // updateNadirPoint
	
	/**
	 * Update lower boundary and upper boundary
	 */
	void updateBoundary() {
		for (int i = 0; i < problem_.getNumberOfObjectives(); i++) {
			lb[i] = z_[i] - (nz_[i] - z_[i]) / (2 * divide);
			ub[i] = nz_[i] + (nz_[i] - z_[i]) / (2 * divide);
		}
	}
	
	/**
	 * Initialize the weight vectors for subproblems (We only use the data that are already available)
	 */
	public void initUniformWeight() {
		String dataFileName;
		dataFileName = "W" + problem_.getNumberOfObjectives() + "D_"
				+ populationSize_ + ".dat";

		try {
			// Open the file
			FileInputStream fis = new FileInputStream(dataDirectory_ + "/"
					+ dataFileName);
			InputStreamReader isr = new InputStreamReader(fis);
			BufferedReader br = new BufferedReader(isr);

			int i = 0;
			int j = 0;
			String aux = br.readLine();
			while (aux != null) {
				StringTokenizer st = new StringTokenizer(aux);
				j = 0;
				while (st.hasMoreTokens()) {
					double value = (new Double(st.nextToken())).doubleValue();
					lambda_[i][j] = value;
					j++;
				}
				aux = br.readLine();
				i++;
			}
			br.close();
		} catch (Exception e) {
			System.out
					.println("initUniformWeight: failed when reading for file: "
							+ dataDirectory_ + "/" + dataFileName);
			e.printStackTrace();
		}
	} // initUniformWeight
	
	/**
	 * Initialize neighborhood structure
	 */
	public void initNeighborhood() {
		double[] x = new double[populationSize_];
		int[] idx = new int[populationSize_];

		for (int i = 0; i < populationSize_; i++) {
			// calculate the distances based on weight vectors
			for (int j = 0; j < populationSize_; j++) {
				x[j] = Utils.distVector(lambda_[i], lambda_[j]);
				idx[j] = j;
			} // for

			// find 'niche' nearest neighboring subproblems
			Utils.minFastSort(x, idx, populationSize_, T_);
			for (int k = 0; k < T_; k++) {
				neighborhood_[i][k] = idx[k];
			}
		} // for
	} // initNeighborhood
	
	/**
	 * Update the utility of each subproblem
	 */
	public void comp_utility() {
		double f1, f2, uti, delta;
		for (int n = 0; n < populationSize_; n++) {
			f1 = fitnessFunction(population_.get(n), lambda_[n]);
			f2 = fitnessFunction(savedValues_[n], lambda_[n]);
			delta = f2 - f1;
			if (delta > 0.001)
				utility_[n] = 1.0;
			else {
				uti = (0.95 + (0.05 * delta / 0.001)) * utility_[n];
				utility_[n] = uti < 1.0 ? uti : 1.0;
			}
			savedValues_[n] = new Solution(population_.get(n));
		}
	}
	
	/**
	 * Tournament selection for active subproblems
	 * @param depth
	 * @return
	 */
	public List<Integer> tour_selection(int depth) {

		// selection based on utility
		List<Integer> selected = new ArrayList<Integer>();
		List<Integer> candidate = new ArrayList<Integer>();

		for (int k = 0; k < problem_.getNumberOfObjectives(); k++)
			selected.add(k); // WARNING! HERE YOU HAVE TO USE THE WEIGHT PROVIDED BY QINGFU (NOT SORTED!!!!)

		for (int n = problem_.getNumberOfObjectives(); n < populationSize_; n++)
			candidate.add(n); // set of unselected weights

		while (selected.size() < (int) (populationSize_ / 5.0)) {
			int best_idd = (int) (PseudoRandom.randDouble() * candidate.size());
			int i2;
			int best_sub = candidate.get(best_idd);
			int s2;
			for (int i = 1; i < depth; i++) {
				i2 = (int) (PseudoRandom.randDouble() * candidate.size());
				s2 = candidate.get(i2);
				if (utility_[s2] > utility_[best_sub]) {
					best_idd = i2;
					best_sub = s2;
				}
			}
			selected.add(best_sub);
			candidate.remove(best_idd);
		}
		return selected;
	}
	
	/**
	 * Mating selection
	 * @param list : the set of the indexes of selected mating parents
	 * @param cid  : the id of current subproblem
	 * @param size : the number of selected mating parents
	 * @param type : 1 - neighborhood; otherwise - whole population
	 */
	public void matingSelection(Vector<Integer> list, int cid, int size,
			int type) {
		int ss, r, p;

		ss = neighborhood_[cid].length;
		while (list.size() < size) {
			if (type == 1) {
				r = PseudoRandom.randInt(0, ss - 1);
				p = neighborhood_[cid][r];
				// p = population[cid].table[r];
			} else {
				p = PseudoRandom.randInt(0, populationSize_ - 1);
			}
			boolean flag = true;
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i) == p) // p is in the list
				{
					flag = false;
					break;
				}
			}

			// if (flag) list.push_back(p);
			if (flag) {
				list.addElement(p);
			}
		}
	} // matingSelection

	public void updateArchive(Solution Child, SolutionSet population) {
		int[] end = new int[1];
		end[0] = 0;
		if (population.size() != 0) {
			for (int j = 0; j < population.size() && end[0] != 1; j++) {
				Solution Parent = population.get(j);
				switch (Check_box_dominance(Child, Parent)) {
				case 1: { /* Child dominates Parent */
					population.remove(j);
					Delete_Parent(Parent, epsilon_neighbour);
					break;
				}
				case 2: { /* Parent dominates Child */
					return;
				}
				case 3: { /* both are non-dominated and are in different boxes */
					break;
				}
				case 4: { /* both are non-dominated and are in same hyper-box */
					end[0] = 1;
					switch (Check_Dominance(Child, Parent)) {
					case 1: {
						population.replace(j, Child);
						Delete_Parent(Parent, epsilon_neighbour);
						Add_Child(Child, epsilon_neighbour);
						break;
					}
					case -1: {
						return;
					}
					case 0: {
						double width;
						double corner;
						double d1 = 0.0;
						double d2 = 0.0;
						for (int i = 0; i < problem_.getNumberOfObjectives(); i++) {
							width  = (ub[i] - lb[i]) / divide;
							corner = lb[i] + width * (int) Math.floor((Child.getObjective(i) - lb[i]) / width);
							d1 += Math.pow((Child.getObjective(i) - corner) / width, 2.0);
							d2 += Math.pow((Parent.getObjective(i) - corner) / width, 2.0);
						}
						if (d1 <= d2) {
							population.replace(j, Child);
							Delete_Parent(Parent, epsilon_neighbour);
							Add_Child(Child, epsilon_neighbour);
						}
						break;
					}
					}
					break;
				}
				}
			}
			if (end[0] == 0) {
				population.add(Child);
				Add_Child(Child, epsilon_neighbour);
			}

		} else if (population.size() == 0) {
			population.add(Child);
			Add_Child(Child, epsilon_neighbour);
		}

	}

	/**
	 * @param individual
	 * @param id
	 * @param type
	 */
	void updateProblem(Solution indiv, int id, int type) {
		// indiv: child solution
		// id: the id of current subproblem
		// type: update solutions in - neighborhood (1) or whole population
		// (otherwise)
		Setlocate(indiv);
		double[] f = new double[3];
		Solution pSolution = population_.get(indiv.read_location());
		f[0] = fitnessFunction(indiv, lambda_[indiv.read_location()]);
		f[1] = fitnessFunction(pSolution, lambda_[pSolution.read_location()]);
		if (f[0] < f[1]) {
			population_.replace(indiv.read_location(), indiv);
		}		

	} // updateProblem

	double fitnessFunction(Solution individual, double[] lambda) {
		double fitness;
		fitness = 0.0;
	
		if (functionType_.equals("_TCHE1")) {
			double maxFun = -1.0e+30;
	
			for (int n = 0; n < problem_.getNumberOfObjectives(); n++) {
				double diff = Math.abs(individual.getObjective(n) - z_[n]);
	
				double feval;
				if (lambda[n] == 0) {
					feval = 0.0001 * diff;
				} else {
					feval = diff * lambda[n];
				}
				if (feval > maxFun) {
					maxFun = feval;
				}
			} // for
	
			fitness = maxFun;
		} else if (functionType_.equals("_TCHE2")) {
			double maxFun = -1.0e+30;
	
			for (int i = 0; i < problem_.getNumberOfObjectives(); i++) {
				double diff = Math.abs(individual.getObjective(i) - z_[i]);
	
				double feval;
				if (lambda[i] == 0) {
					feval = diff / 0.000001;
				} else {
					feval = diff / lambda[i];
				}
				if (feval > maxFun) {
					maxFun = feval;
				}
			} // for
			fitness = maxFun;
			} else if (functionType_.equals("_PBI")) {
				double theta; // penalty parameter
				theta = 5.0;

				// normalize the weight vector (line segment)
				double nd = Utils.norm_vector(lambda, problem_.getNumberOfObjectives());
				for (int i = 0; i < problem_.getNumberOfObjectives(); i++)
					lambda[i] = lambda[i] / nd;

				double[] realA = new double[problem_.getNumberOfObjectives()];
				double[] realB = new double[problem_.getNumberOfObjectives()];

				// difference between current point and reference point
				for (int n = 0; n < problem_.getNumberOfObjectives(); n++)
					realA[n] = (individual.getObjective(n) - z_[n]);

				// distance along the line segment
				double d1 = Math.abs(Utils.innerproduct(realA, lambda));

				// distance to the line segment
				for (int n = 0; n < problem_.getNumberOfObjectives(); n++)
					realB[n] = (individual.getObjective(n) - (z_[n] + d1 * lambda[n]));
				double d2 = Utils.norm_vector(realB, problem_.getNumberOfObjectives());

				fitness = d1 + theta * d2;
			} else {
			System.out.println("MOEAD.fitnessFunction: unknown type "
					+ functionType_);
			System.exit(-1);
		}
		return fitness;
	} // fitnessEvaluation

	/**
	 * Add a solution into the register
	 * @param Child
	 * @param epsilon_neighbour
	 */
	public void Add_Child(Solution Child,
			LinkedList<SolutionSet> epsilon_neighbour) {
		SolutionSet a = epsilon_neighbour.get(Child.read_location());

		a.add(Child);
	}
	
	/**
	 * Delete a solution from the register
	 * @param Parent
	 * @param epsilon_neighbour
	 */
	public void Delete_Parent(Solution Parent,
			LinkedList<SolutionSet> epsilon_neighbour) {
		SolutionSet a = epsilon_neighbour.get(Parent.read_location());
		int i = 0;
		Solution z = a.get(i);
		while (!z.equals(Parent)) {
			i++;
			if (i == a.size()) {
				return;
			}
			z = a.get(i);
		}
		a.remove(i);
	}

	/**
	 * Set the location of an offspring solution
	 * @param Child
	 */
	public void Setlocate(Solution Child) {
		double sum[] = new double[problem_.getNumberOfObjectives() + 1];
		for (int i = 0; i < problem_.getNumberOfObjectives(); i++) {
			sum[0] = sum[0] + Child.getObjective(i);
			sum[i + 1] = Child.getObjective(i);
		}
		for (int i = 0; i < problem_.getNumberOfObjectives(); i++) {
			sum[i + 1] = sum[i + 1] / sum[0]; // normalization
		}
		double[] k = Find_Divide(sum);
		Child.Set_location((int) k[1]);
		Child.Set_diversity(k[0]);
	}

	/**
	 * Find the associated subregion
	 * @param sum
	 * @return
	 */
	public double[] Find_Divide(double[] sum) {
		double key[] = new double[2];
		key[0] = 1e10;
		for (int i = 0; i < populationSize_; i++) {
			if (problem_.getNumberOfObjectives() == 3) {
				double z = Math.pow(sum[1] - lambda_[i][0], 2.0)
						+ Math.pow(sum[2] - lambda_[i][1], 2.0)
						+ Math.pow(sum[3] - lambda_[i][2], 2.0);
				if (z < key[0]) { 
					key[0] = z;
					key[1] = i;
				}
			} else if (problem_.getNumberOfObjectives() == 2) {
				double z = Math.pow(sum[1] - lambda_[i][0], 2.0)
						+ Math.pow(sum[2] - lambda_[i][1], 2.0);
				if (z < key[0]) {
					key[0] = z;
					key[1] = i;
				}
			}
		}
		
		return key;
	}

	/**
	 * Check Pareto dominance relation
	 * @param a
	 * @param b
	 * @return
	 */
	public int Check_Dominance(Solution a, Solution b) {
		int[] flag1 = new int[1];
		int[] flag2 = new int[1];

		for (int i = 0; i < problem_.getNumberOfObjectives(); i++) {
			if (a.getObjective(i) < b.getObjective(i)) {
				flag1[0] = 1;
			} else {
				if (a.getObjective(i) > b.getObjective(i)) {
					flag2[0] = 1;
				}
			}
		}
		if (flag1[0] == 1 && flag2[0] == 0) {
			return 1;
		} else {
			if (flag1[0] == 0 && flag2[0] == 1) {
				return -1;
			} else {
				return 0;
			}
		}
	}

	/**
	 * Check grid-dominance relation
	 * @param a
	 * @param b
	 * @return
	 */
	public int Check_box_dominance(Solution a, Solution b) {
		double width;
		int[] flag1 = new int[1];
		int[] flag2 = new int[1];

		for (int i = 0; i < problem_.getNumberOfObjectives(); i++) {
			width = (ub[i] - lb[i]) / divide;
			
			if ((int) Math.floor((a.getObjective(i) - lb[i]) / width) < (int) Math
					.floor((b.getObjective(i) - lb[i]) / width)) {
				flag1[0] = 1;
			} else {
				if ((int) Math.floor((a.getObjective(i) - lb[i])
						/ width) > (int) Math
						.floor((b.getObjective(i) - lb[i]) / width)) {
					flag2[0] = 1;
				}
			}
		}
		if (flag1[0] == 1 && flag2[0] == 0) {
			return 1;
		} else {
			if (flag1[0] == 0 && flag2[0] == 1) {
				return 2;
			} else {
				if (flag1[0] == 1 && flag2[0] == 1) {
					return 3;
				} else {
					return 4;
				}
			}
		}
	}
	
	public SolutionSet filtering(int final_size) {
		int popsize = populationSize_ + archive_pop.size();
		
		mixed_pop = new SolutionSet(popsize);
		for (int i = 0; i < populationSize_; i++) {
			mixed_pop.add(population_.get(i));
		}
		for (int i = 0; i < archive_pop.size(); i++) {
			mixed_pop.add(archive_pop.get(i));
		}
		
		return mixed_pop;
	}

} // DPP_Grid

